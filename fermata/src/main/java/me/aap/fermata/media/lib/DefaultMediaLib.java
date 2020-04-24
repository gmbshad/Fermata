package me.aap.fermata.media.lib;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.support.v4.media.MediaBrowserCompat.MediaItem;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import me.aap.fermata.BuildConfig;
import me.aap.fermata.media.engine.MediaEngineManager;
import me.aap.fermata.media.engine.MetadataRetriever;
import me.aap.fermata.media.pref.BrowsableItemPrefs;
import me.aap.fermata.media.pref.MediaLibPrefs;
import me.aap.fermata.vfs.FermataVfsManager;
import me.aap.utils.async.Async;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.collection.CollectionUtils;
import me.aap.utils.event.BasicEventBroadcaster;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.pref.SharedPreferenceStore;

import static me.aap.utils.async.Completed.completed;
import static me.aap.utils.async.Completed.completedEmptyList;
import static me.aap.utils.async.Completed.completedNull;
import static me.aap.utils.async.Completed.completedVoid;

/**
 * @author Andrey Pavlenko
 */
public class DefaultMediaLib extends BasicEventBroadcaster<PreferenceStore.Listener>
		implements MediaLib, MediaLibPrefs, SharedPreferenceStore {
	private static final String ID = "Root";
	private final Context ctx;
	private final SharedPreferences sharedPreferences;
	private final DefaultFolders folders;
	private final DefaultFavorites favorites;
	private final DefaultPlaylists playlists;
	private final MediaEngineManager mediaEngineManager;
	private final MetadataRetriever metadataRetriever;
	private final Map<String, WeakRef<Item>> itemCache = new HashMap<>();
	private final ReferenceQueue<Item> itemRefQueue = new ReferenceQueue<>();

	public DefaultMediaLib(Context ctx) {
		this.ctx = ctx;
		sharedPreferences = ctx.getSharedPreferences("medialib", Context.MODE_PRIVATE);
		folders = new DefaultFolders(this);
		favorites = new DefaultFavorites(this);
		playlists = new DefaultPlaylists(this);
		mediaEngineManager = new MediaEngineManager(this);
		metadataRetriever = new MetadataRetriever(mediaEngineManager);
	}

	@NonNull
	public String getRootId() {
		return ID;
	}

	@NonNull
	@Override
	public Context getContext() {
		return ctx;
	}

	@NonNull
	@Override
	public MediaLibPrefs getPrefs() {
		return this;
	}

	@NonNull
	@Override
	public SharedPreferences getSharedPreferences() {
		return sharedPreferences;
	}

	@NonNull
	@Override
	public FutureSupplier<Item> getItem(CharSequence itemId) {
		String id = itemId.toString();
		Item i = getFromCache(id);
		if (i != null) return completed(i);

		int idx = id.indexOf(':');

		if (idx == -1) {
			switch (id) {
				case DefaultFolders.ID:
					return completed(getFolders());
				case DefaultFavorites.ID:
					return completed(getFavorites());
				case DefaultPlaylists.ID:
					return completed(getPlaylists());
				default:
					return completedNull();
			}
		}

		switch (id.substring(0, idx)) {
			case FileItem.SCHEME:
				return FileItem.create(this, id);
			case FolderItem.SCHEME:
				return FolderItem.create(this, id);
			case CueItem.SCHEME:
				return CueItem.create(this, id);
			case CueTrackItem.SCHEME:
				return CueTrackItem.create(this, id);
			case M3uItem.SCHEME:
				return M3uItem.create(this, id);
			case M3uGroupItem.SCHEME:
				return M3uGroupItem.create(this, id);
			case M3uTrackItem.SCHEME:
				return M3uTrackItem.create(this, id);
			case DefaultFavorites.SCHEME:
				return getFavorites().getItem(id);
			case DefaultPlaylists.SCHEME:
				return getPlaylists().getItem(id);
			default:
				return completedNull();
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public void getChildren(String parentMediaId, MediaLibResult<List<MediaItem>> result) {
		result.detach();

		if (getRootId().equals(parentMediaId)) {
			List<MediaItem> items = new ArrayList<>(4);

			Async.forEach(f -> {
						MediaItem i = f.get();
						if (i != null) items.add(i);
						return completedVoid();
					},
					getLastPlayedItem().then(i -> (i == null) ? completedNull() : i.asMediaItem()),
					getFolders().asMediaItem(),
					getFavorites().asMediaItem(),
					getPlaylists().asMediaItem()
			).onFailure(this::log).onCompletion((r, f) -> result.sendResult(items, null));
		} else {
			getItem(parentMediaId).then(i -> {
				if (i instanceof BrowsableItem) {
					return ((BrowsableItem) i).getChildren().then(children -> {
						if (children.isEmpty()) return completedEmptyList();
						List<MediaItem> items = new ArrayList<>(children.size());
						return Async.forEach(c -> c.asMediaItem().map(items::add), children).map(r -> items);
					});
				} else {
					return completedEmptyList();
				}
			}).onFailure(this::log).onCompletion((r, f) -> result.sendResult(r, null));
		}
	}

	@Override
	public void getItem(String itemId, MediaLibResult<MediaItem> result) {
		result.detach();
		getItem(itemId)
				.then(Item::asMediaItem)
				.onFailure(this::log)
				.onCompletion((i, f) -> result.sendResult(i, null));
	}

	@Override
	public void search(String query, MediaLibResult<List<MediaItem>> result) {
		// TODO Implement
	}

	@NonNull
	@Override
	public FutureSupplier<PlayableItem> getLastPlayedItem() {
		String id = getLastPlayedItemPref();
		if (id == null) return completedNull();
		return getItem(id).then(i -> {
			if (i instanceof PlayableItem) return completed((PlayableItem) i);
			if (i instanceof BrowsableItem) return ((BrowsableItem) i).getFirstPlayable();
			return completedNull();
		});
	}

	@Override
	public long getLastPlayedPosition(PlayableItem i) {
		if (i.isVideo()) return i.getPrefs().getPositionPref();
		BrowsableItemPrefs p = i.getParent().getPrefs();
		String id = p.getLastPlayedItemPref();
		return ((id != null) && id.equals(i.getId())) ? p.getLastPlayedPosPref() : 0;
	}

	@Override
	public void setLastPlayed(PlayableItem i, long position) {
		String id;
		BrowsableItemPrefs p;
		long dur = i.getDuration().getOrThrow();

		if (dur <= 0) {
			id = i.getId();
			p = i.getParent().getPrefs();
			setLastPlayedItemPref(id);
			setLastPlayedPosPref(0);
			p.setLastPlayedItemPref(id);
			p.setLastPlayedPosPref(0);
			return;
		}

		if ((dur - position) <= 1000) {
			i.getNextPlayable().onCompletion((next, fail) -> {
				if (next == null) next = i;

				String nextId = next.getId();
				BrowsableItemPrefs nextPrefs = next.getParent().getPrefs();
				setLastPlayedItemPref(nextId);
				setLastPlayedPosPref(0);
				nextPrefs.setLastPlayedItemPref(nextId);
				nextPrefs.setLastPlayedPosPref(0);
			});

			return;
		} else {
			id = i.getId();
			p = i.getParent().getPrefs();
		}

		if (i.isVideo()) {
			if (position > (dur * 0.9f)) {
				i.getPrefs().setWatchedPref(true);
			} else {
				i.getPrefs().setPositionPref(position);
			}
		}

		setLastPlayedItemPref(id);
		setLastPlayedPosPref(position);
		p.setLastPlayedItemPref(id);
		p.setLastPlayedPosPref(position);
	}

	@NonNull
	@Override
	public DefaultFolders getFolders() {
		return folders;
	}

	@NonNull
	@Override
	public DefaultFavorites getFavorites() {
		return favorites;
	}

	@NonNull
	@Override
	public DefaultPlaylists getPlaylists() {
		return playlists;
	}

	@NonNull
	@Override
	public FermataVfsManager getVfsManager() {
		return folders.getVfsManager();
	}

	@NonNull
	@Override
	public MediaEngineManager getMediaEngineManager() {
		return mediaEngineManager;
	}

	@NonNull
	@Override
	public MetadataRetriever getMetadataRetriever() {
		return metadataRetriever;
	}

	@NonNull
	@Override
	public FutureSupplier<Bitmap> getBitmap(String uri, boolean cache, boolean resize) {
		return getMetadataRetriever().getBitmapCache().getBitmap(getContext(), uri, cache, resize);
	}

	Object cacheLock() {
		return itemCache;
	}

	void addToCache(Item i) {
		synchronized (itemCache) {
			clearRefs(itemCache, itemRefQueue);
			if (BuildConfig.DEBUG && itemCache.containsKey(i.getId())) throw new AssertionError();
			itemCache.put(i.getId(), new WeakRef<>(i.getId(), i, itemRefQueue));
		}
	}

	void removeFromCache(Item i) {
		synchronized (itemCache) {
			clearRefs(itemCache, itemRefQueue);
			if (i == null) return;
			String id = i.getId();
			WeakRef<Item> r = itemCache.get(id);
			if (r == null) return;
			Item cached = r.get();
			if ((cached == null) || (cached == i)) itemCache.remove(id);
		}
	}

	Item getFromCache(String id) {
		synchronized (itemCache) {
			clearRefs(itemCache, itemRefQueue);
			WeakRef<Item> r = itemCache.get(id);

			if (r != null) {
				Item cached = r.get();
				if (cached != null) return cached;
				else itemCache.remove(id);
			}
		}

		return null;
	}

	@SuppressWarnings("rawtypes")
	private static void clearRefs(Map map, ReferenceQueue q) {
		for (Ref r = (Ref) q.poll(); r != null; r = (Ref) q.poll()) {
			CollectionUtils.remove(map, r.key(), r);
		}
	}

	private void log(Throwable ex) {
		Log.e(getClass().getName(), "Error occurred", ex);
	}

	private interface Ref {
		Object key();
	}

	private static final class WeakRef<V> extends WeakReference<V> implements Ref {
		final Object key;

		public WeakRef(Object key, V value, ReferenceQueue<V> q) {
			super(value, q);
			this.key = key;
		}

		public Object key() {
			return key;
		}
	}
}
