package com.kamesuta.itemownertracker;

import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BlockVector;

import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.stream.Collectors;

public final class ItemOwnerTracker extends JavaPlugin implements Listener {
    /**
     * CoreProtectAPI
     */
    private CoreProtectAPI api;

    /**
     * 追跡対象のプレイヤー名
     */
    private List<String> targetUsers;

    /**
     * 通知用のWebhookのURL
     */
    private String webhookUrl;

    /**
     * 対象ユーザーのアイテムをチェストに入れた履歴
     */
    private Map<String, List<BlockVector>> blockHistory;

    @Override
    public void onEnable() {
        // Plugin startup logic

        // コンフィグを生成
        saveDefaultConfig();

        // CoreProtectAPIの取得
        api = CoreProtect.getInstance().getAPI();

        // 追跡対象のプレイヤー名を取得
        targetUsers = getConfig().getStringList("target_players");
        if (targetUsers.isEmpty()) {
            getLogger().warning("追跡対象のプレイヤーが設定されていません。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 通知用のWebhookのURLを取得
        webhookUrl = getConfig().getString("webhook_url");
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            getLogger().warning("通知用のWebhookのURLが設定されていません。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 対象ユーザーのアイテムをチェストに入れた履歴を取得
        blockHistory = getBlockHistory(targetUsers);

        // リスナーの登録
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }

    /**
     * チェストを開いたときの処理
     */
    @EventHandler
    public void onInventoryClick(PlayerInteractEvent event) {
        // 右クリック以外は無視
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        // クリックしたブロックを取得
        Block block = event.getClickedBlock();
        if (block == null) return;

        // 検証
        validateBlock(block, event.getPlayer());
    }

    /**
     * ブロックを壊したときの処理
     */
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        // 壊したブロックを取得
        Block block = event.getBlock();

        // 検証
        validateBlock(block, event.getPlayer());
    }

    /**
     * ブロックを検証
     */
    private void validateBlock(Block block, Player player) {
        // 座標を取得
        BlockVector blockVector = block.getLocation().toVector().toBlockVector();
        // 履歴があるか検証
        List<BlockVector> blockHistoryMap = blockHistory.get(block.getWorld().getName());
        if (blockHistoryMap == null) return;
        if (!blockHistoryMap.contains(blockVector)) return;

        // 指定ユーザーのアイテム入れた履歴を取得
        List<String[]> lookupResult = lookupItemPutAction(targetUsers, block.getLocation());

        // プレイヤーリスト
        List<String> playerList = lookupResult.stream()
                .map(result -> api.parseResult(result))
                .map(CoreProtectAPI.ParseResult::getPlayer)
                .filter(name -> !player.getName().equals(name)) // 自分の名前は除外
                .distinct()
                .collect(Collectors.toList());

        // プレイヤーがいない場合は無視
        if (playerList.isEmpty()) return;

        // ログ設定
        String message = String.format("[要注意人物] %s がアイテムを入れたチェストを %s が開きました: (world: %s, x:%d, y:%d, z:%d)", String.join(", ", playerList), player.getName(), block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        // ログに出力
        getLogger().warning(message);
        // Discordに通知
        try {
            notifyWebhook(message);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, message, e);
        }
    }

    /**
     * Webhookで通知
     */
    private void notifyWebhook(String message) throws IOException {
        // WebhookのJSONデータを作成
        String json = String.format("{\"content\": \"%s\"}", message);

        // https://qiita.com/nururuv/items/b269af6ac5ac472ceab1
        // 1. 接続するための設定をする

        // URL に対して openConnection メソッドを呼び出すし、接続オブジェクトを生成する
        URL url = new URL(webhookUrl);
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();

        // HttpURLConnectionの各種設定
        //HTTPのメソッドをPOSTに設定
        conn.setRequestMethod("POST");
        //リクエストボディへの書き込みを許可
        conn.setDoInput(true);
        //レスポンスボディの取得を許可
        conn.setDoOutput(true);
        //リクエスト形式をJsonに指定
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

        // 2.接続を確立する
        conn.connect();

        // 3.リクエスとボディに書き込みを行う
        //HttpURLConnectionからOutputStreamを取得し、json文字列を書き込む
        PrintStream ps = new PrintStream(conn.getOutputStream());
        ps.print(json);
        ps.close();

        // 4.レスポンスを受け取る
        //正常終了時はHttpStatusCode 204が返ってくる
        if (conn.getResponseCode() != 204) {
            //エラー処理
            throw new IOException("HTTPエラー: " + conn.getResponseCode());
        }

        // 5.InputStreamを閉じる
        conn.disconnect();
    }

    /**
     * 対象ユーザーがアイテムをチェストに入れた履歴を取得する
     *
     * @param targetUsers 対象ユーザーリスト
     * @return 履歴 (ワールド名→座標のリスト のマップ)
     */
    private Map<String, List<BlockVector>> getBlockHistory(List<String> targetUsers) {
        // 指定ユーザーのアイテム入れた履歴を取得
        List<String[]> lookupResult = lookupItemPutAction(targetUsers, null);

        // ワールド名→座標のリストのマップに変換して返す
        return lookupResult.stream()
                .map(result -> api.parseResult(result))
                .collect(Collectors.groupingBy(CoreProtectAPI.ParseResult::worldName, Collectors.mapping(result -> new BlockVector(result.getX(), result.getY(), result.getZ()), Collectors.toList())));
    }

    /**
     * 対象ユーザーがアイテムをチェストに入れたアクションを検索する
     *
     * @param targetUsers 対象ユーザーリスト
     * @param location    ブロックの座標 (nullの場合は全ての座標)
     * @return 検索結果
     */
    private List<String[]> lookupItemPutAction(List<String> targetUsers, Location location) {
        // 1ヶ月≒4週間=28日
        long time = TimeUnit.DAYS.toSeconds(28);
        // アイテムに入れたとき (action:+container)
        List<Integer> action = createItemPutActionFilter();
        // 指定ユーザーのアイテム入れた履歴を取得
        return api.performLookup((int) time, targetUsers, null, null, null, action, -1, location);
    }

    /**
     * アイテムを入れたときのCoreProtectアクション (action:+container) フィルターを作成
     */
    private List<Integer> createItemPutActionFilter() {
        // アイテムに入れたとき (action:+container)
        return new ArrayList<Integer>() {
            {
                // チェスト操作フラグ + 設置フラグ = チェストに入れたとき
                add(4); // チェスト操作
                add(1); // 設置
            }

            @Override
            public boolean removeIf(Predicate<? super Integer> filter) {
                // performLookup内で4(container)が除外されてしまうため、オーバーライドして対応
                return true;
            }
        };
    }
}
