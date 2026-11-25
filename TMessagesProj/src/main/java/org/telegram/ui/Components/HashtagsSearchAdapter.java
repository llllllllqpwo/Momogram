package org.telegram.ui.Components;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Adapters.MessagesSearchAdapter;
import org.telegram.ui.Stories.StoriesController;

import java.util.ArrayList;

import moe.hx030.momogram.MomoConfig;

public class HashtagsSearchAdapter extends UniversalAdapter {

    private final int currentAccount;
    private final ArrayList<MessageObject> messages = new ArrayList<>();
    public boolean hasList;
    public StoriesController.SearchStoriesList list;

    public HashtagsSearchAdapter(RecyclerListView listView, Context context, int currentAccount, int folderId, Theme.ResourcesProvider resourcesProvider) {
        super(listView, context, currentAccount, 0, null, resourcesProvider);
        super.fillItems = this::fillItems;
        this.currentAccount = currentAccount;
    }

    private boolean hadStories;
    private int blacklistVersion = -1;

    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        final boolean pruned = pruneBlacklistedMessages();
        boolean hasStories = hasList && list != null && list.getLoadedCount() > 0;
        if (hasStories) {
            items.add(MessagesSearchAdapter.StoriesView.Factory.asStoriesList(list));
        }
        hadStories = hasStories;
        int shown = 0;
        for (int i = 0; i < messages.size(); ++i) {
            final MessageObject msg = messages.get(i);
            // 兜底：即使列表里残留屏蔽前的旧数据，也不进入渲染。
            if (MomoConfig.isSearchBlacklistedMessage(msg.messageOwner)) {
                continue;
            }
            items.add(UItem.asSearchMessage(1 + (shown++), msg));
        }
        if (loading || !endReached) {
            items.add(UItem.asFlicker(-2, FlickerLoadingView.DIALOG_TYPE));
            items.add(UItem.asFlicker(-3, FlickerLoadingView.DIALOG_TYPE));
            items.add(UItem.asFlicker(-4, FlickerLoadingView.DIALOG_TYPE));
        }
        if (!hadStories && hasStories) {
            AndroidUtilities.runOnUIThread(() -> {
                scrollToTop(true);
            });
        }
        if (pruned && shown == 0 && !endReached && !loading && !TextUtils.isEmpty(lastQuery)) {
            AndroidUtilities.runOnUIThread(() -> {
                if (!loading) {
                    search(lastQuery);
                }
            });
        }
    }

    /** 黑名单变化时由搜索界面主动调用：立刻把已显示的公开贴文重新过滤，必要时续拉下一页。 */
    public void onBlacklistChanged() {
        final boolean pruned = pruneBlacklistedMessages();
        update(true);
        int visible = 0;
        for (MessageObject msg : messages) {
            if (!MomoConfig.isSearchBlacklistedMessage(msg.messageOwner)) visible++;
        }
        if (pruned && visible == 0 && !endReached && !loading && !TextUtils.isEmpty(lastQuery)) {
            final String query = lastQuery;
            AndroidUtilities.runOnUIThread(() -> {
                if (!loading) {
                    search(query);
                }
            });
        }
    }

    /** 黑名单发生变化时，把此前已经缓存在内存里的公开贴文清掉，并按新数量修正翻页计数。 */
    private boolean pruneBlacklistedMessages() {
        if (blacklistVersion == MomoConfig.searchBlacklistVersion) {
            return false;
        }
        blacklistVersion = MomoConfig.searchBlacklistVersion;
        final int before = messages.size();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (MomoConfig.isSearchBlacklistedMessage(messages.get(i).messageOwner)) {
                messages.remove(i);
            }
        }
        final int removed = before - messages.size();
        if (removed > 0) {
            hiddenCount += removed;
            totalCount = Math.max(messages.size(), totalCount - removed);
            return true;
        }
        return false;
    }

    protected boolean loading;
    private int searchId = 0;
    private int reqId = -1;
    private boolean endReached;
    private int totalCount;
    private int hiddenCount;
    private String lastQuery;
    private String hashtagQuery;
    private int lastRate;
    private int lastOffsetId;
    private TLRPC.Peer lastOffsetPeer;
    private Runnable searchRunnable;

    public void setInitialData(String hashtag, ArrayList<MessageObject> messages, int messagesLastRate, int totalCount) {
        if (TextUtils.equals(hashtag, hashtagQuery)) return;
        cancel();
        this.messages.clear();
        int hidden = 0;
        for (MessageObject msg : messages) {
            if (msg == null) continue;
            if (MomoConfig.isSearchBlacklistedMessage(msg.messageOwner)) {
                hidden++;
                continue;
            }
            this.messages.add(msg);
        }
        hiddenCount = hidden;
        this.totalCount = Math.max(0, totalCount);
        endReached = this.totalCount > this.messages.size();
        this.lastRate = messagesLastRate;
        hashtagQuery = hashtag;
        blacklistVersion = MomoConfig.searchBlacklistVersion;
        update(true);
    }

    private final boolean[] cashtag = new boolean[1];

    public void search(String query) {
        lastQuery = query;
        final String hashtag = getHashtag(query, cashtag);
        if (!TextUtils.equals(this.hashtagQuery, hashtag)) {
            messages.clear();
            endReached = false;
            totalCount = 0;
            hiddenCount = 0;
            lastRate = 0;
            lastOffsetId = 0;
            lastOffsetPeer = null;
            cancel();
        } else if (loading) {
            return;
        }
        final int id = ++searchId;
        if (hashtag == null) return;
        loading = true;
        update(true);
        AndroidUtilities.runOnUIThread(searchRunnable = () -> {
            if (id != searchId) {
                return;
            }
            final String finalQuery = (cashtag[0] ? "$" : "#") + hashtagQuery;
            if (list == null || !TextUtils.equals(list.query, finalQuery)) {
                list = new StoriesController.SearchStoriesList(currentAccount, null, finalQuery);
            }
            if (list.getLoadedCount() <= 0) {
                list.load(true, 4);
            }
            hasList = true;
            final TLRPC.TL_channels_searchPosts req = new TLRPC.TL_channels_searchPosts();
            req.flags |= 1;
            req.hashtag = this.hashtagQuery = hashtag;
            req.limit = 10;
            if (lastOffsetPeer != null) {
                req.offset_rate = lastRate;
                req.offset_id = lastOffsetId;
                req.offset_peer = MessagesController.getInstance(currentAccount).getInputPeer(lastOffsetPeer);
            } else {
                req.offset_peer = new TLRPC.TL_inputPeerEmpty();
            }
            reqId = ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
                if (id != searchId) {
                    return;
                }
                final boolean wasEmpty = messages.isEmpty();
                loading = false;
                if (res instanceof TLRPC.messages_Messages) {
                    TLRPC.messages_Messages msgs = (TLRPC.messages_Messages) res;
                    if (msgs instanceof TLRPC.TL_messages_messages) {
                        totalCount = ((TLRPC.TL_messages_messages) msgs).messages.size();
                    } else if (msgs instanceof TLRPC.TL_messages_messagesSlice) {
                        totalCount = ((TLRPC.TL_messages_messagesSlice) msgs).count;
                    }
                    lastRate = msgs.next_rate;
                    MessagesController.getInstance(currentAccount).putUsers(msgs.users, false);
                    MessagesController.getInstance(currentAccount).putChats(msgs.chats, false);
                    int hidden = 0;
                    for (int i = 0; i < msgs.messages.size(); ++i) {
                        final TLRPC.Message msg = msgs.messages.get(i);
                        if (MomoConfig.isSearchBlacklistedMessage(msg)) {
                            hidden++;
                            continue;
                        }
                        final MessageObject messageObject = new MessageObject(currentAccount, msg, false, true);
                        messageObject.setQuery(finalQuery);
                        messages.add(messageObject);
                    }
                    // 翻页锚点取服务端原始返回的最后一条，避免整页被屏蔽后锚点丢失导致重复拉取。
                    if (!msgs.messages.isEmpty()) {
                        final TLRPC.Message lastRaw = msgs.messages.get(msgs.messages.size() - 1);
                        lastOffsetId = lastRaw.realId != 0 ? lastRaw.realId : lastRaw.id;
                        lastOffsetPeer = lastRaw.peer_id;
                    }
                    hiddenCount += hidden;
                    totalCount = Math.max(0, totalCount - hiddenCount);
                    endReached = messages.size() >= totalCount || msgs.messages.isEmpty();
                    checkBottom();
                } else {
                    endReached = true;
                    totalCount = messages.size();
                }
                update(true);
                if (wasEmpty) {
                    scrollToTop(false);
                }
            }));
        }, 300);
    }

    public String getHashtag(String query) {
        return getHashtag(query, null);
    }

    public String getHashtag(String query, final boolean[] cashtag) {
        if (cashtag != null) cashtag[0] = false;
        if (query == null || query.isEmpty()) return null;
        String tquery = query.trim();
        if (tquery.length() <= 1) return null;
        if (tquery.charAt(0) != '#' && tquery.charAt(0) != '$') return null;
        if (tquery.indexOf('@') >= 0) return null;
        if (cashtag != null) cashtag[0] = tquery.charAt(0) == '$';
        return tquery.substring(1);
    }

    public void cancel() {
        if (list != null) {
            list.cancel();
        }
        hasList = false;
        if (reqId >= 0) {
            ConnectionsManager.getInstance(currentAccount).cancelRequest(reqId, true);
            reqId = -1;
        }
        AndroidUtilities.cancelRunOnUIThread(searchRunnable);
        searchId++;
        loading = false;
    }


    public void checkBottom() {
        if (!TextUtils.isEmpty(lastQuery)) {
            if (!endReached && !loading && seesLoading()) {
                search(lastQuery);
            }
        }
    }

    public boolean seesLoading() {
        if (listView == null) return false;
        for (int i = 0; i < listView.getChildCount(); ++i) {
            View child = listView.getChildAt(i);
            if (child instanceof FlickerLoadingView) {
                return true;
            }
        }
        return false;
    }

    protected void scrollToTop(boolean ifAtTop) {

    }

}
