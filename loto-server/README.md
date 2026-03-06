# 🎱 Loto Server

Server game lô tô multiplayer viết bằng Java. Hỗ trợ kết nối TCP và WebSocket, quản lý ví tiền người chơi, bot AI tự động, và lưu trạng thái game.

---

## 🚀 Khởi động

```bash
java -jar loto-server.jar [transport] [options]
```

### Chế độ transport

| Lệnh | Mô tả |
|---|---|
| `--tcp [port]` | Chỉ TCP (mặc định port 9000) |
| `--ws [port]` | Chỉ WebSocket (mặc định port 9001) |
| `--both [tcpPort] [wsPort]` | TCP + WebSocket đồng thời **(mặc định)** |

### Ví dụ

```bash
# Chạy mặc định (TCP 9000 + WS 9001)
java -jar loto-server.jar

# Chỉ TCP
java -jar loto-server.jar --tcp 9000

# Chỉ WebSocket
java -jar loto-server.jar --ws 9001

# Đầy đủ tùy chọn
java -jar loto-server.jar --both 9000 9001 --price 5000 --interval 3000 --persist save.json --auto-reset 30000
```

---

## ⚙️ Tham số CLI

| Tham số | Mặc định | Mô tả |
|---|---|---|
| `--port <int>` | `9000` | Cổng TCP |
| `--ws-port <int>` | `tcpPort+1` | Cổng WebSocket |
| `--interval <ms>` | `5000` | Thời gian giữa 2 lần quay số (ms) |
| `--price <long>` | `10000` | Giá mỗi tờ lô tô (đồng) |
| `--initial-balance <long>` | `0` | Số dư ban đầu mỗi người chơi |
| `--max-pages <int>` | `10` | Số tờ tối đa mỗi lần mua |
| `--min-players <int>` | `1` | Số người tối thiểu để bắt đầu game |
| `--vote-threshold <int>` | `51` | Phần trăm vote để tự động bắt đầu |
| `--reconnect-timeout <ms>` | `30000` | Thời gian chờ kết nối lại |
| `--persist <path>` | tắt | Đường dẫn file lưu trạng thái JSON |
| `--auto-reset <ms>` | `0` (tắt) | Tự reset phòng sau khi game kết thúc |
| `--auto-verify` | `false` | Server tự xác minh kình (không cần host) |

---

## 🖥️ Lệnh console (runtime)

Gõ lệnh trực tiếp vào terminal khi server đang chạy.

### 📋 Xem thông tin

| Lệnh | Mô tả |
|---|---|
| `status` hoặc `players` | Danh sách người chơi, jackpot, cài đặt hiện tại |
| `help` | Xem toàn bộ lệnh |

### 🎮 Điều khiển game

| Lệnh | Mô tả |
|---|---|
| `start` | Bắt đầu game ngay (bỏ qua vote) |
| `end` | Kết thúc game (không có người thắng) |
| `cancel [lý do]` | Hủy game và hoàn tiền toàn bộ |
| `reset` | Reset phòng về WAITING |

### ⚙️ Cài đặt runtime (thay đổi khi đang chạy)

| Lệnh | Mô tả |
|---|---|
| `speed <ms>` | Đổi tốc độ quay số (ms), áp dụng ngay |
| `price <số tiền>` | Đổi giá tờ lô tô (chỉ khi chưa ai mua) |
| `autoreset <giây>` | Đặt thời gian tự reset (giây), `0` = tắt |

### 💰 Quản lý người chơi

| Lệnh | Mô tả |
|---|---|
| `topup <playerId> <số tiền> [ghi chú]` | Nạp tiền cho người chơi |
| `kick <playerId> [lý do]` | Kick người chơi khỏi phòng |
| `ban <playerId> [lý do]` | Cấm người chơi (theo tên + IP) |
| `unban <tên>` | Bỏ cấm theo tên |
| `banip <ip>` | Cấm theo địa chỉ IP |
| `unbanip <ip>` | Bỏ cấm IP |
| `banlist` | Xem danh sách bị cấm |

### ✅ Xác nhận kình (khi `--auto-verify` tắt)

| Lệnh | Mô tả |
|---|---|
| `confirm <playerId> <pageId>` | Xác nhận người thắng |
| `reject <playerId> <pageId>` | Từ chối kình |

### 🤖 Bot

| Lệnh | Mô tả |
|---|---|
| `bot add <tên> [maxTờ] [số dư]` | Thêm bot (mặc định: maxTờ=3, số dư=999999) |
| `bot remove <tên>` | Xóa bot |
| `bot list` | Danh sách bot đang trong phòng |

---

## 🤖 Hệ thống Bot

Bot hoạt động như người chơi thật nhưng tự động:

- **Mua giấy**: ngay sau khi join (delay ngẫu nhiên 0.3–1 giây), mua 1..maxTờ tờ ngẫu nhiên
- **Dò số**: kiểm tra từng tờ sau mỗi lần quay số
- **Claim kình**: tự động claim khi có hàng trúng (delay 50–400ms cho tự nhiên)
- **Reset**: tự mua lại đầu ván mới, không cần thêm lại
- **Tồn tại**: bot ở trong phòng cho đến khi host dùng `bot remove`

```bash
# Thêm bot tên "Tèo", mua tối đa 5 tờ, số dư 1 triệu
bot add Tèo 5 1000000

# Thêm bot mặc định
bot add Bot1
```

---

## 📡 WebSocket Protocol

Kết nối tới `ws://host:port`

### Client → Server

Tất cả message dạng JSON:

```json
{ "type": "TÊN_LỆNH", "payload": { ... } }
```

| type | payload | Mô tả |
|---|---|---|
| `JOIN` | `{ "name": "Tèo" }` | Vào phòng |
| `RECONNECT` | `{ "token": "abc..." }` | Kết nối lại sau ngắt |
| `BUY_PAGE` | `{ "count": 3 }` | Mua tờ lô tô |
| `VOTE_START` | _(trống)_ | Vote bắt đầu game |
| `CLAIM_WIN` | `{ "pageId": 5 }` | Báo kình |
| `GET_WALLET` | _(trống)_ | Lấy lịch sử giao dịch |
| `CONFIRM_WIN` ⭐ | `{ "playerId": "x", "pageId": 5 }` | Xác nhận kình (host) |
| `REJECT_WIN` ⭐ | `{ "playerId": "x", "pageId": 5 }` | Từ chối kình (host) |
| `TOPUP` ⭐ | `{ "playerId": "x", "amount": 50000 }` | Nạp tiền (host) |
| `CANCEL_GAME` ⭐ | `{ "reason": "..." }` | Hủy game (host) |
| `KICK` ⭐ | `{ "playerId": "x", "reason": "..." }` | Kick (host) |
| `BAN` ⭐ | `{ "playerId": "x", "reason": "..." }` | Cấm (host) |
| `UNBAN` ⭐ | `{ "name": "Tèo" }` | Bỏ cấm (host) |
| `SET_DRAW_INTERVAL` ⭐ | `{ "intervalMs": 3000 }` | Đổi tốc độ (host) |
| `SET_PRICE_PER_PAGE` ⭐ | `{ "price": 20000 }` | Đổi giá tờ (host) |
| `SET_AUTO_RESET` ⭐ | `{ "delayMs": 30000 }` | Đặt auto-reset (host) |

> ⭐ = chỉ host mới dùng được

### Server → Client

| type | Mô tả |
|---|---|
| `WELCOME` | Gửi riêng khi join thành công — có token, playerId, danh sách tờ |
| `ROOM_UPDATE` | Broadcast khi có thay đổi trong phòng — danh sách players, jackpot |
| `PLAYER_JOINED` | Có người mới vào phòng |
| `PLAYER_LEFT` | Người chơi thoát |
| `GAME_STARTING` | Game bắt đầu |
| `NUMBER_DRAWN` | Một số vừa được quay |
| `VOTE_UPDATE` | Cập nhật số phiếu vote |
| `CLAIM_RECEIVED` | Ai đó báo kình |
| `WIN_CONFIRMED` | Kình được xác nhận |
| `WIN_REJECTED` | Kình bị từ chối |
| `GAME_ENDED` | Game kết thúc (có người thắng) |
| `GAME_ENDED_BY_SERVER` | Game kết thúc (hết số / server kết thúc) |
| `GAME_CANCELLED` | Game bị hủy — kèm số tiền hoàn |
| `ROOM_RESET` | Phòng reset, ván mới bắt đầu |
| `BALANCE_UPDATE` | Cập nhật số dư sau giao dịch |
| `WALLET_HISTORY` | Toàn bộ lịch sử giao dịch |
| `DRAW_INTERVAL_CHANGED` | Tốc độ quay số đã thay đổi |
| `PRICE_PER_PAGE_CHANGED` | Giá tờ đã thay đổi |
| `AUTO_RESET_SCHEDULED` | Thông báo thời gian tự reset |
| `KICKED` | Bạn bị kick khỏi phòng |
| `BANNED` | Bạn bị cấm khỏi phòng |
| `ERROR` | Lỗi — có `code` và `message` |

---

## 🔄 Luồng game

```
WAITING → (vote đủ hoặc host start) → PLAYING → (có người kình) → ENDED → (reset) → WAITING
                                          ↓
                                    (cancel) → ENDED (hoàn tiền)
```

1. Người đầu tiên join = **host**
2. Mọi người mua tờ (WAITING / VOTING)
3. Vote hoặc host gõ `start`
4. Server quay số theo interval
5. Ai có hàng trúng → gửi `CLAIM_WIN`
6. Host xác nhận hoặc server tự xác minh (nếu `--auto-verify`)
7. Jackpot chia đều cho tất cả người thắng
8. Phòng tự reset (nếu `--auto-reset`) hoặc host gõ `reset`

---

## 💾 Persistence

Bật bằng `--persist save.json`. Server tự lưu sau mỗi thay đổi trạng thái. Khi khởi động lại, server đọc file và phục hồi:
- Trạng thái game (state, số đã quay, jackpot)
- Danh sách người chơi (token để kết nối lại, số dư, tờ đang giữ)
- Cài đặt phòng (price, speed, auto-reset)
- Danh sách bị cấm

> Người chơi reconnect bằng cách gửi `RECONNECT` với `token` nhận được khi join ban đầu.

---

## 🏗️ Cấu trúc source

```
src/main/java/com/loto/
├── Main.java                 # Entry point + console commands
├── core/
│   ├── GameRoom.java         # Logic phòng chơi
│   ├── BotManager.java       # Quản lý bot
│   ├── LotoServer.java       # Server chính
│   ├── ServerConfig.java     # Cấu hình (Builder pattern)
│   ├── GameState.java        # WAITING / VOTING / PLAYING / ENDED
│   └── TransportMode.java    # TCP / WS / BOTH
├── model/
│   ├── Player.java           # Người chơi
│   ├── BotPlayer.java        # Bot (extends Player)
│   ├── LotoPage.java         # Tờ lô tô + logic kiểm tra kình
│   ├── PlayerInfo.java       # Snapshot gửi cho client
│   └── Transaction.java      # Lịch sử giao dịch ví
├── network/
│   ├── IClientHandler.java   # Interface kết nối (TCP + WS)
│   ├── ClientHandler.java    # TCP handler
│   ├── WebSocketServer.java  # WS server
│   ├── WebSocketClientHandler.java
│   └── MessageDispatcher.java # Xử lý message từ client
├── protocol/
│   ├── InboundMsg.java       # Parse message từ client
│   ├── OutboundMsg.java      # Build message gửi client
│   └── MsgType.java          # Enum các loại message
└── persist/
    └── JsonPersistence.java  # Lưu/đọc trạng thái JSON
```