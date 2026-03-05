# loto-server-lib

Plain JAR library. No frameworks, no Android dependencies — runs on both **JVM (PC)** and **Android**.

---

## Build

```bash
./gradlew jar
# Output: build/libs/loto-server-1.0.0.jar
```

---

## Dependency (org.json only)

| Library   | Version    |
|-----------|------------|
| org.json  | 20240303   |

---

## Usage — PC (Java)

```java
LotoServer server = new LotoServer.Builder()
        .port(9000)
        .drawIntervalMs(5_000)   // 5 seconds between draws
        .callback(new LotoServerCallback() {

            @Override
            public void onPlayerJoined(Player player) {
                System.out.println("Joined: " + player.getName());
            }

            @Override
            public void onNumberDrawn(int number, List<Integer> drawn) {
                System.out.println("Drew: " + number);
            }

            @Override
            public void onClaimReceived(Player player, int pageId) {
                System.out.println(player.getName() + " claims page " + pageId);
                // Verify manually, then:
                server.getRoom().confirmWin(player.getId(), pageId);
                // or:
                server.getRoom().rejectWin(player.getId(), pageId);
            }

            // ... implement remaining methods
        })
        .build();

// Blocks — run in its own thread if needed
new Thread(() -> {
    try { server.start(); }
    catch (IOException e) { e.printStackTrace(); }
}).start();
```

---

## Usage — Android (Kotlin)

```kotlin
val server = LotoServer.Builder()
    .port(9000)
    .drawIntervalMs(5_000)
    .callback(object : LotoServerCallback {

        override fun onPlayerJoined(player: Player) {
            runOnUiThread { playerListAdapter.add(player.name) }
        }

        override fun onNumberDrawn(number: Int, drawn: List<Int>) {
            runOnUiThread { updateDrawnNumbers(drawn) }
        }

        override fun onClaimReceived(player: Player, pageId: Int) {
            runOnUiThread {
                // Show a confirm/reject dialog to the host
                showClaimDialog(player, pageId,
                    onConfirm = { server.room.confirmWin(player.id, pageId) },
                    onReject  = { server.room.rejectWin(player.id,  pageId) }
                )
            }
        }

        // ... implement remaining methods
    })
    .build()

// Start on background thread (NOT on main thread)
lifecycleScope.launch(Dispatchers.IO) {
    server.start()
}

// Stop when done
override fun onDestroy() {
    server.stop()
    super.onDestroy()
}
```

---

## Protocol (newline-delimited JSON over TCP)

### Client → Server

| type         | payload fields             |
|--------------|---------------------------|
| `JOIN`       | `name`                    |
| `RECONNECT`  | `token`                   |
| `BUY_PAGE`   | `count`                   |
| `VOTE_START` | *(empty)*                 |
| `CLAIM_WIN`  | `pageId`                  |
| `CONFIRM_WIN`| `playerId`, `pageId`      |
| `REJECT_WIN` | `playerId`, `pageId`      |

### Server → Client

| type             | payload fields                          |
|------------------|-----------------------------------------|
| `WELCOME`        | `playerId`, `token`, `isHost`, `pages`  |
| `PLAYER_JOINED`  | `playerId`, `name`, `isHost`            |
| `PLAYER_LEFT`    | `playerId`                              |
| `PAGES_ASSIGNED` | `playerId`, `pages`                     |
| `VOTE_UPDATE`    | `voteCount`, `needed`                   |
| `GAME_STARTING`  | `drawIntervalMs`                        |
| `NUMBER_DRAWN`   | `number`, `drawnList`                   |
| `CLAIM_RECEIVED` | `playerId`, `playerName`, `pageId`      |
| `WIN_CONFIRMED`  | `playerId`, `playerName`, `pageId`      |
| `WIN_REJECTED`   | `playerId`, `pageId`                    |
| `GAME_ENDED`     | `winnerPlayerId`, `winnerName`          |
| `ERROR`          | `code`, `message`                       |

---

## Game flow

```
WAITING  ──(enough votes)──▶  VOTING  ──(threshold reached)──▶  PLAYING  ──(win confirmed)──▶  ENDED
                                                                     │
                                                            numbers drawn every
                                                            drawIntervalMs ms
```

---

## Reconnect flow

1. Client receives `token` in `WELCOME` — store it persistently.  
2. On reconnect, send `{ "type": "RECONNECT", "payload": { "token": "..." } }`.  
3. Server replays drawn numbers and resends pages.  
4. If token not seen within **30 seconds** of disconnect, player is removed permanently.
