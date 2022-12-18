# ItemOwnerTracker

事前にマークしたプレイヤーがアイテムを入れたチェストに別アカウントでアクセスしたときに通知を飛ばすプラグインです

突貫工事なので
- コマンドは未実装です。config.ymlをいじってプラグインをリロードしてください。
- 通知を減らす実装はしていません。チェストを開けるたびに通知が飛びます。

注意点
- このプラグインはCoreProtectが必要です
- テストバージョン: Minecraft 1.19.2, CoreProtect 21.2

## 設定

config.yml
```yaml
# 追跡対象者
target_players:
  - 'test' # ← ここに追跡対象者の名前を追加してください

# 通知用 Webhook URL
webhook_url: https://discord.com/api/webhooks/～～～ # ← ここに通知用の Webhook URL を追加してください
```
