# loto-client-lib

Client library để kết nối vào `loto-server-lib`.  
Plain JAR — chạy được trên **JVM (PC)** và **Android**.

---

## Build

```bash
./gradlew jar
# → build/libs/loto-client-1.0.0.jar
```

---

## Usage — PC (Java)

```java
LotoClient client = new LotoClient.Builder()
        .host("192.168.1.10")
        .port(9000)
        .playerName("Nguyen")
        .autoReconnect(true)
        .autoClaimOnWin(false)   // true = tự báo kình khi có hàng hoàn thành
        .callback(new LotoClientCallback() {

            @Override
            public void onJoined(String playerId, String token, boolean isHost) {
                System.out.println("Joined as " + playerId);
                // Lưu token để reconnect sau
            }

            @Override
            public void onPagesAssigned(List<ClientPage> newPages) {
                for (ClientPage page : newPages) {
                    System.out.println("Got page #" + page.getId());
                }
            }

            @Override
            public void onNumberDrawn(int number, List<Integer> drawn,
                                      List<ClientPage> markedPages,
                                      List<ClientPage> wonPages) {
                System.out.println("Drew: " + number);
                // wonPages đã được auto-mark, chỉ cần render lại
            }

            @Override
            public void onPageWon(ClientPage page, int rowIndex) {
                System.out.println("Kình! Page #" + page.getId() + " row " + rowIndex);
                // Nếu autoClaimOnWin=false, tự quyết định có claim không:
                client.claimWin(page.getId());
            }

            // ... implement remaining methods
        })
        .build();

// Blocks — run on background thread
new Thread(client::connect).start();

// Actions
client.buyPages(3);
client.voteStart();
client.claimWin(pageId);
client.requestWalletHistory();
```

---

## Usage — Android (Kotlin)

```kotlin
// Inject LotoClientManager (Hilt Singleton)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val manager: LotoClientManager
) : ViewModel() {

    val uiState = manager.uiState
    val events  = manager.events

    fun connect(host: String, name: String) {
        manager.connect(
            host           = host,
            port           = 9000,
            playerName     = name,
            autoReconnect  = true,
            autoClaimOnWin = false,
        )
    }

    fun buyPages(count: Int) = manager.buyPages(count)
    fun voteStart()          = manager.voteStart()
    fun claimWin(pageId: Int) = manager.claimWin(pageId)
}

// Compose UI
@Composable
fun PlayerScreen(vm: PlayerViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is PlayerEvent.NumberDrawn -> { /* animate số vừa quay */ }
                is PlayerEvent.PageWon     -> { /* show kình dialog */ }
                is PlayerEvent.GameEnded   -> { /* show winner */ }
                else -> Unit
            }
        }
    }
}
```

---

## ClientPage API

```java
ClientPage page = ...;

// Render grid
for (int r = 0; r < 9; r++) {
    for (int c = 0; c < 9; c++) {
        Integer number  = page.getGrid().get(r).get(c);  // null = ô trống
        boolean marked  = page.isMarked(r, c);
        // render cell
    }
}

// Check win
boolean won       = page.hasWon();
int     winRow    = page.getWinningRowIndex();   // -1 nếu chưa thắng
List<Integer> completedRows = page.getCompletedRows();
```

---

## Auto-reconnect flow

1. Nhận `token` trong `onJoined` — lưu lại (SharedPreferences / ViewModel).
2. Khi mất kết nối, client tự gửi `RECONNECT { token }`.
3. Server replay drawn numbers → client tự restore marks trên tất cả tờ.
4. `onReconnected()` được gọi khi hoàn tất.

---

## Module graph

```
:features:player          (Compose UI)
    └── :core:loto-client-android  (Kotlin wrapper + StateFlow)
         └── loto-client-1.0.0.jar (Java core)
              └── TCP → loto-server-1.0.0.jar
```
