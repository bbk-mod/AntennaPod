package de.danoeh.antennapod.storage.database;

import android.content.Context;

import de.danoeh.antennapod.event.QueueEvent;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.net.download.serviceinterface.AutoDownloadManager;
import de.danoeh.antennapod.net.download.serviceinterface.DownloadServiceInterface;
import de.danoeh.antennapod.net.download.serviceinterface.DownloadServiceInterfaceStub;
import de.danoeh.antennapod.net.sync.serviceinterface.SynchronizationQueue;
import de.danoeh.antennapod.net.sync.serviceinterface.SynchronizationQueueStub;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;
import de.danoeh.antennapod.storage.preferences.UserPreferences;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class DBWriterQueueTest {
    private Context context;
    private QueueEventCollector eventCollector;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        UserPreferences.init(context);
        PlaybackPreferences.init(context);
        PodDBAdapter.init(context);
        PodDBAdapter.deleteDatabase();
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        adapter.close();
        SynchronizationQueue.setInstance(new SynchronizationQueueStub());
        DownloadServiceInterface.setImpl(new DownloadServiceInterfaceStub());
        AutoDownloadManager.setInstance(new AutoDownloadManager() {
            @Override
            public Future<?> autodownloadUndownloadedItems(Context context) {
                return null;
            }

            @Override
            public void performAutoCleanup(Context context) {
            }
        });
        eventCollector = new QueueEventCollector();
        EventBus.getDefault().register(eventCollector);
    }

    @After
    public void tearDown() {
        EventBus.getDefault().unregister(eventCollector);
        DBWriter.tearDownTests();
    }

    @Test
    public void testAddNewItemAfterCurrentlyPlaying() throws Exception {
        Feed feed = createFeedWithItems(5);
        List<FeedItem> items = feed.getItems();
        DBWriter.addQueueItem(context, items.get(0), items.get(1), items.get(2)).get();
        PlaybackPreferences.writeMediaPlaying(items.get(0).getMedia());

        DBWriter.addQueueItemAfterCurrentlyPlaying(context, items.get(3)).get();

        assertQueueIds(items.get(0).getId(), items.get(3).getId(),
                items.get(1).getId(), items.get(2).getId());
    }

    @Test
    public void testMoveAlreadyQueuedItemAfterCurrentlyPlaying() throws Exception {
        Feed feed = createFeedWithItems(4);
        List<FeedItem> items = feed.getItems();
        DBWriter.addQueueItem(context, items.get(0), items.get(1), items.get(2)).get();
        PlaybackPreferences.writeMediaPlaying(items.get(0).getMedia());

        DBWriter.addQueueItemAfterCurrentlyPlaying(context, items.get(2)).get();

        assertQueueIds(items.get(0).getId(), items.get(2).getId(), items.get(1).getId());
    }

    @Test
    public void testMoveEmitsRemovedThenAdded() throws Exception {
        Feed feed = createFeedWithItems(4);
        List<FeedItem> items = feed.getItems();
        DBWriter.addQueueItem(context, items.get(0), items.get(1), items.get(2)).get();
        PlaybackPreferences.writeMediaPlaying(items.get(0).getMedia());
        eventCollector.events.clear();

        DBWriter.addQueueItemAfterCurrentlyPlaying(context, items.get(2)).get();

        QueueEvent removed = eventCollector.find(QueueEvent.Action.REMOVED, items.get(2).getId());
        QueueEvent added = eventCollector.find(QueueEvent.Action.ADDED, items.get(2).getId());
        assertNotNull(removed);
        assertNotNull(added);
        assertEquals(1, added.position);
        assertTrue(eventCollector.events.indexOf(removed) < eventCollector.events.indexOf(added));
    }

    @Test
    public void testMultipleItemsKeepTheirOrder() throws Exception {
        Feed feed = createFeedWithItems(6);
        List<FeedItem> items = feed.getItems();
        DBWriter.addQueueItem(context, items.get(0), items.get(1)).get();
        PlaybackPreferences.writeMediaPlaying(items.get(0).getMedia());

        DBWriter.addQueueItemAfterCurrentlyPlaying(context, items.get(2), items.get(3), items.get(4)).get();

        assertQueueIds(items.get(0).getId(), items.get(2).getId(), items.get(3).getId(),
                items.get(4).getId(), items.get(1).getId());
    }

    @Test
    public void testAnchorKeepsOrderAcrossCalls() throws Exception {
        Feed feed = createFeedWithItems(8);
        List<FeedItem> items = feed.getItems();
        DBWriter.addQueueItem(context, items.get(0), items.get(1)).get();
        PlaybackPreferences.writeMediaPlaying(items.get(0).getMedia());

        DBWriter.addQueueItemAfterCurrentlyPlaying(context, items.get(2), items.get(3)).get();
        DBWriter.addQueueItemAfter(context, items.get(3), items.get(4), items.get(5)).get();

        assertQueueIds(items.get(0).getId(), items.get(2).getId(), items.get(3).getId(),
                items.get(4).getId(), items.get(5).getId(), items.get(1).getId());
    }

    @Test
    public void testAnchorKeepsOrderWhenPlaybackAdvances() throws Exception {
        Feed feed = createFeedWithItems(6);
        List<FeedItem> items = feed.getItems();
        DBWriter.addQueueItem(context, items.get(0), items.get(1)).get();
        PlaybackPreferences.writeMediaPlaying(items.get(0).getMedia());

        DBWriter.addQueueItemAfterCurrentlyPlaying(context, items.get(2), items.get(3)).get();
        PlaybackPreferences.writeMediaPlaying(items.get(2).getMedia());
        DBWriter.addQueueItemAfter(context, items.get(3), items.get(4)).get();

        assertQueueIds(items.get(0).getId(), items.get(2).getId(), items.get(3).getId(),
                items.get(4).getId(), items.get(1).getId());
    }

    @Test
    public void testAnchorNotInQueueFallsBackToAfterCurrentlyPlaying() throws Exception {
        Feed feed = createFeedWithItems(5);
        List<FeedItem> items = feed.getItems();
        DBWriter.addQueueItem(context, items.get(0), items.get(1)).get();
        PlaybackPreferences.writeMediaPlaying(items.get(0).getMedia());

        DBWriter.addQueueItemAfter(context, items.get(4), items.get(2)).get();

        assertQueueIds(items.get(0).getId(), items.get(2).getId(), items.get(1).getId());
    }

    @Test
    public void testSelectedItemIsCurrentlyPlayingIsSkipped() throws Exception {
        Feed feed = createFeedWithItems(3);
        List<FeedItem> items = feed.getItems();
        DBWriter.addQueueItem(context, items.get(0), items.get(1)).get();
        PlaybackPreferences.writeMediaPlaying(items.get(0).getMedia());

        DBWriter.addQueueItemAfterCurrentlyPlaying(context, items.get(0)).get();

        assertQueueIds(items.get(0).getId(), items.get(1).getId());
    }

    @Test
    public void testCurrentlyPlayingNotInQueueInsertsAtFront() throws Exception {
        Feed feed = createFeedWithItems(4);
        List<FeedItem> items = feed.getItems();
        DBWriter.addQueueItem(context, items.get(0), items.get(1)).get();
        PlaybackPreferences.writeMediaPlaying(items.get(2).getMedia());

        DBWriter.addQueueItemAfterCurrentlyPlaying(context, items.get(3)).get();

        assertQueueIds(items.get(3).getId(), items.get(0).getId(), items.get(1).getId());
    }

    @Test
    public void testNothingPlayingInsertsAtFront() throws Exception {
        Feed feed = createFeedWithItems(4);
        List<FeedItem> items = feed.getItems();
        DBWriter.addQueueItem(context, items.get(0), items.get(1)).get();
        PlaybackPreferences.writeNoMediaPlaying();

        DBWriter.addQueueItemAfterCurrentlyPlaying(context, items.get(2)).get();

        assertQueueIds(items.get(2).getId(), items.get(0).getId(), items.get(1).getId());
    }

    private void assertQueueIds(long... expectedIds) throws Exception {
        DBWriter.tearDownTests();
        List<FeedItem> queue = DBReader.getQueue();
        assertEquals(expectedIds.length, queue.size());
        for (int i = 0; i < expectedIds.length; i++) {
            assertEquals(expectedIds[i], queue.get(i).getId());
        }
    }

    private Feed createFeedWithItems(int numItems) {
        Feed feed = new Feed(0, null, "title", "http://example.com", "This is the description",
                "http://example.com/payment", "Daniel", "en", null, "http://example.com/feed",
                "http://example.com/image", null, "http://example.com/feed", System.currentTimeMillis());
        List<FeedItem> items = new ArrayList<>();
        for (int i = 0; i < numItems; i++) {
            FeedItem item = new FeedItem(0, "Item " + i, "id " + i, "link " + i,
                    new Date(i), FeedItem.UNPLAYED, feed);
            item.setMedia(new FeedMedia(item, "http://example.com/audio/" + i, 1000, "audio/mpeg"));
            items.add(item);
        }
        feed.setItems(items);

        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        adapter.setCompleteFeed(feed);
        adapter.close();

        DBWriter.tearDownTests();
        return DBReader.getFeed(feed.getId(), false, 0, Integer.MAX_VALUE);
    }

    public static class QueueEventCollector {
        public final List<QueueEvent> events = new CopyOnWriteArrayList<>();

        @Subscribe
        public void onQueueEvent(QueueEvent event) {
            events.add(event);
        }

        public QueueEvent find(QueueEvent.Action action, long itemId) {
            for (QueueEvent event : events) {
                if (event.action == action && event.item != null && event.item.getId() == itemId) {
                    return event;
                }
            }
            return null;
        }
    }
}
