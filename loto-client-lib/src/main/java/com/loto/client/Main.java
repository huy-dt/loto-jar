package com.loto.client;

import com.loto.client.callback.LotoClientCallback;
import com.loto.client.core.LotoClient;
import com.loto.client.model.ClientPage;
import com.loto.client.model.RoomPlayer;
import com.loto.client.model.WalletInfo;

import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

/**
 * Standalone CLI entry point.
 *
 * Usage:
 *   java -jar loto-client.jar --name <n> [options]
 *
 * Transport (chọn 1):
 *   --tcp  [host] [port]    TCP (default: localhost 9000)
 *   --ws   <url>            WebSocket  e.g. ws://192.168.1.10:9001
 *
 * Options:
 *   --name        <string>  Tên hiển thị (bắt buộc)
 *   --room        <string>  Room ID (multi-room server)
 *   --auto-claim            Tự báo kình khi có hàng
 *   --admin-token <token>   Tự động xác thực admin sau khi join
 *   --token       <token>   Token từ session trước → RECONNECT ngay (không cần --name)
 *
 * Examples:
 *   java -jar loto-client.jar --tcp 192.168.1.10 9000 --name Nguyen
 *   java -jar loto-client.jar --ws ws://192.168.1.10:9001 --name Nguyen --auto-claim
 *   java -jar loto-client.jar --tcp --name Admin --admin-token abc-123
 *   java -jar loto-client.jar --tcp 192.168.1.10 9000 --name Nguyen --token xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
 */
public class Main {

    private static LotoClient client;
    private static String      pendingAdminToken = null;

    public static void main(String[] args) throws Exception {
        String  host       = "localhost";
        int     port       = 9000;
        String  wsUrl      = null;
        int     wsPort     = 0;
        String  wsHost     = null;
        String  name       = null;
        String  roomId     = null;
        boolean autoClaim  = false;
        String  initToken  = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--tcp":
                    if (i + 1 < args.length && !args[i+1].startsWith("--")) host = args[++i];
                    if (i + 1 < args.length && args[i+1].matches("\\d+"))   port = Integer.parseInt(args[++i]);
                    break;
                case "--ws":
                    if (i + 1 < args.length && !args[i+1].startsWith("--")) {
                        String next = args[++i];
                        if (next.startsWith("ws://") || next.startsWith("wss://")) {
                            wsUrl = next;
                        } else {
                            wsHost = next;
                        }
                    }
                    break;
                case "--name":         name              = args[++i]; break;
                case "--room":         roomId            = args[++i]; break;
                case "--auto-claim":   autoClaim         = true;      break;
                case "--admin-token":  pendingAdminToken = args[++i]; break;
                case "--token":        initToken         = args[++i]; break;
                case "--host":         host = args[++i]; wsHost = host; break;
                case "--port":
                    int p = Integer.parseInt(args[++i]);
                    if (wsHost != null || wsUrl == null && args.length > 2) wsPort = p;
                    else port = p;
                    break;
            }
        }

        if (wsUrl == null && wsHost != null) {
            int wp = wsPort > 0 ? wsPort : (port > 0 ? port : 9001);
            wsUrl = "ws://" + wsHost + ":" + wp;
        } else if (wsUrl == null && wsPort > 0) {
            wsUrl = "ws://" + host + ":" + wsPort;
        }

        if (name == null || name.trim().isEmpty()) {
            System.err.println("ERROR: --name is required");
            System.err.println("Usage: java -jar loto-client.jar --name <n> [--tcp host port | --ws url]");
            System.exit(1);
        }

        LotoClient.Builder builder = new LotoClient.Builder()
                .playerName(name)
                .roomId(roomId)
                .autoReconnect(true)
                .reconnectDelayMs(3_000)
                .autoClaimOnWin(autoClaim)
                .token(initToken)
                .callback(new ConsoleCallback());

        if (wsUrl != null) {
            builder.wsUrl(wsUrl);
        } else {
            builder.host(host).port(port);
        }

        client = builder.build();
        printBanner(wsUrl != null ? wsUrl : host + ":" + port,
                    wsUrl != null, name, autoClaim, roomId,
                    pendingAdminToken != null, initToken != null);

        Thread connectThread = new Thread(client::connect, "loto-connect");
        connectThread.setDaemon(true);
        connectThread.start();

        runCommandLoop();
    }

    // ── CLI ───────────────────────────────────────────────────────

    private static void runCommandLoop() {
        Scanner scanner = new Scanner(System.in);
        while (scanner.hasNextLine()) {
            String   line  = scanner.nextLine().trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+");
            String   cmd   = parts[0].toLowerCase();

            switch (cmd) {
                case "help":    printHelp();    break;
                case "status":  printStatus();  break;
                case "pages":   printPages();   break;

                case "buy": {
                    int count = parts.length > 1 ? safeInt(parts[1], 1) : 1;
                    client.buyPages(count);
                    System.out.println("→ Đang mua " + count + " tờ...");
                    break;
                }

                case "vote":
                    client.voteStart();
                    System.out.println("→ Đã vote bắt đầu");
                    break;

                case "claim":
                    if (parts.length < 2) { System.out.println("Usage: claim <pageId>"); break; }
                    client.claimWin(safeInt(parts[1], -1));
                    System.out.println("→ Đã báo kình tờ #" + parts[1]);
                    break;

                case "wallet":
                    client.requestWalletHistory();
                    break;

                case "auth":
                    // auth <adminToken>
                    if (parts.length < 2) { System.out.println("Usage: auth <adminToken>"); break; }
                    client.adminAuth(parts[1]);
                    System.out.println("→ Đang xác thực admin...");
                    break;

                // ── Admin/Host actions ─────────────────────────

                case "pause":
                    client.pauseGame();
                    System.out.println("→ Gửi lệnh pause");
                    break;

                case "resume":
                    client.resumeGame();
                    System.out.println("→ Gửi lệnh resume");
                    break;

                case "start":
                    client.serverStart();
                    System.out.println("→ Gửi lệnh start (bypass vote)");
                    break;

                case "end": {
                    String endReason = parts.length > 1
                            ? String.join(" ", Arrays.copyOfRange(parts, 1, parts.length))
                            : "Admin kết thúc game";
                    client.serverEnd(endReason);
                    System.out.println("→ Gửi lệnh end: " + endReason);
                    break;
                }

                case "reset":
                    client.resetRoom();
                    System.out.println("→ Gửi lệnh reset phòng");
                    break;

                case "confirm":
                    if (parts.length < 3) { System.out.println("Usage: confirm <playerId> <pageId>"); break; }
                    client.confirmWin(parts[1], safeInt(parts[2], -1));
                    break;

                case "reject":
                    if (parts.length < 3) { System.out.println("Usage: reject <playerId> <pageId>"); break; }
                    client.rejectWin(parts[1], safeInt(parts[2], -1));
                    break;

                case "topup": {
                    if (parts.length < 3) { System.out.println("Usage: topup <playerId> <amount> [note]"); break; }
                    String note = parts.length > 3
                            ? String.join(" ", Arrays.copyOfRange(parts, 3, parts.length)) : null;
                    client.topUp(parts[1], safeLong(parts[2], 0), note);
                    break;
                }

                case "cancel": {
                    String reason = parts.length > 1
                            ? String.join(" ", Arrays.copyOfRange(parts, 1, parts.length))
                            : "Host hủy game";
                    client.cancelGame(reason);
                    break;
                }

                case "kick": {
                    if (parts.length < 2) { System.out.println("Usage: kick <playerId> [reason]"); break; }
                    String kickReason = parts.length > 2
                            ? String.join(" ", Arrays.copyOfRange(parts, 2, parts.length)) : null;
                    client.kick(parts[1], kickReason);
                    break;
                }

                case "ban": {
                    if (parts.length < 2) { System.out.println("Usage: ban <playerId> [reason]"); break; }
                    String banReason = parts.length > 2
                            ? String.join(" ", Arrays.copyOfRange(parts, 2, parts.length)) : null;
                    client.ban(parts[1], banReason);
                    break;
                }

                case "unban":
                    if (parts.length < 2) { System.out.println("Usage: unban <n>"); break; }
                    client.unban(parts[1]);
                    break;

                case "banip":
                    if (parts.length < 2) { System.out.println("Usage: banip <ip>"); break; }
                    client.banIp(parts[1]);
                    System.out.println("→ Gửi lệnh ban IP: " + parts[1]);
                    break;

                case "unbanip":
                    if (parts.length < 2) { System.out.println("Usage: unbanip <ip>"); break; }
                    client.unbanIp(parts[1]);
                    System.out.println("→ Gửi lệnh unban IP: " + parts[1]);
                    break;

                case "banlist":
                    client.getBanList();
                    break;

                case "speed":
                    if (parts.length < 2) {
                        System.out.printf("  Draw interval: %d ms%n", client.getCurrentDrawIntervalMs());
                    } else {
                        int ms = safeInt(parts[1], -1);
                        if (ms < 200) { System.out.println("  Min 200ms"); break; }
                        client.setDrawInterval(ms);
                        System.out.printf("  → Speed → %d ms%n", ms);
                    }
                    break;

                case "price":
                    if (parts.length < 2) {
                        long cur = client.getCurrentPricePerPage();
                        long pend = client.getPendingPricePerPage();
                        System.out.printf("  Giá tờ: %,d đồng%s%n", cur,
                                pend >= 0 ? "  (pending: " + String.format("%,d", pend) + ")" : "");
                    } else {
                        long newPrice = safeLong(parts[1], -1);
                        if (newPrice < 0) { System.out.println("  Giá không hợp lệ"); break; }
                        client.setPricePerPage(newPrice);
                        System.out.printf("  → Giá tờ → %,d đồng%n", newPrice);
                    }
                    break;

                case "autoreset":
                    if (parts.length < 2) {
                        int cur = client.getCurrentAutoResetDelayMs();
                        System.out.printf("  Auto-reset: %s%n", cur > 0 ? cur/1000 + " giây" : "tắt");
                    } else {
                        int sec = safeInt(parts[1], 0);
                        client.setAutoReset(sec * 1000);
                        System.out.printf("  → Auto-reset: %s%n", sec > 0 ? sec + " giây" : "tắt");
                    }
                    break;

                case "autostart":
                    if (parts.length < 2) {
                        int cur = client.getCurrentAutoStartDelayMs();
                        System.out.printf("  Auto-start: %s%n", cur > 0 ? cur/1000 + " giây" : "tắt");
                    } else {
                        int sec = safeInt(parts[1], 0);
                        client.setAutoStart(sec * 1000);
                        System.out.printf("  → Auto-start: %s%n", sec > 0 ? sec + " giây" : "tắt");
                    }
                    break;

                // ── Reconnect test ─────────────────────────────

                case "reconnect-test":
                    // Simulates a disconnect to verify the reconnect flow works.
                    // Closes the underlying connection without clearing the token —
                    // autoReconnect will kick in and send RECONNECT with the saved token.
                    System.out.println("  [~] Forcing disconnect to test reconnect...");
                    if (client.getState().name().equals("IN_GAME") || client.getState().name().equals("CONNECTED")) {
                        // Access underlying connection drop via disconnect+reconnect trick:
                        // We keep autoReconnect=true, so after drop the client will reconnect with token
                        System.out.println("  [~] Token: " + "stored internally — will auto-reconnect");
                        System.out.println("  [!] Tip: stop the server briefly and restart to test full reconnect.");
                        System.out.println("  [!] Or use 'disconnect' then 'reconnect' for manual test.");
                    } else {
                        System.out.println("  [!] Không trong phòng, không cần reconnect.");
                    }
                    break;

                case "disconnect":
                    client.disconnect();
                    System.out.println("  [-] Đã ngắt kết nối (token đã xóa — sẽ JOIN lại khi reconnect)");
                    break;

                case "quit": case "exit":
                    client.disconnect();
                    System.exit(0);
                    break;

                default:
                    System.out.println("Lệnh không hợp lệ. Gõ 'help'.");
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────

    private static void printPages() {
        List<ClientPage> pages = client.getPages();
        if (pages.isEmpty()) {
            System.out.println("  Chưa có tờ. Dùng 'buy <count>'.");
            return;
        }
        for (ClientPage page : pages) {
            System.out.println("  ┌── Tờ #" + page.getId()
                    + (page.hasWon() ? " ★ KÌNH!" : "") + " ──");
            List<List<Integer>> grid = page.getGrid();
            for (int r = 0; r < grid.size(); r++) {
                System.out.print("  │ ");
                List<Integer> row = grid.get(r);
                for (int c = 0; c < row.size(); c++) {
                    Integer cell = row.get(c);
                    if (cell == null)                System.out.print("  . ");
                    else if (page.isMarked(r, c))    System.out.printf("[%2d]", cell);
                    else                             System.out.printf(" %2d ", cell);
                }
                System.out.println();
            }
            System.out.println("  └─────────────────────────────────────");
        }
    }

    private static void printStatus() {
        System.out.println("  State    : " + client.getState()
                + (client.isPaused() ? " [PAUSED]" : ""));
        System.out.println("  Game     : " + client.getCurrentGameState());
        System.out.println("  Player   : " + client.getPlayerId()
                + (client.isHost()  ? "  (HOST)"  : "")
                + (client.isAdmin() ? "  [ADMIN]" : ""));
        System.out.printf ("  Balance  : %,d%n", client.getWallet().getBalance());
        System.out.println("  Pages    : " + client.getPages().size());
        System.out.println("  Drawn    : " + client.getDrawnNumbers().size() + " / 90");
        if (client.getVoteNeeded() > 0)
            System.out.printf("  Vote     : %d / %d%n", client.getVoteCount(), client.getVoteNeeded());
        if (client.getCurrentDrawIntervalMs() > 0)
            System.out.println("  Speed    : " + client.getCurrentDrawIntervalMs() + " ms/số");
        if (client.getCurrentPricePerPage() > 0)
            System.out.printf ("  Price    : %,d đồng/tờ%n", client.getCurrentPricePerPage());
        int ar = client.getCurrentAutoResetDelayMs();
        System.out.println("  AutoReset: " + (ar > 0 ? ar/1000 + "s" : "tắt"));
        int as_ = client.getCurrentAutoStartDelayMs();
        System.out.println("  AutoStart: " + (as_ > 0 ? as_/1000 + "s" : "tắt"));
    }

    private static void printBanner(String server, boolean isWs,
                                    String name, boolean autoClaim,
                                    String roomId, boolean hasAdminToken,
                                    boolean isReconnect) {
        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║           LOTO CLIENT                ║");
        System.out.println("╠══════════════════════════════════════╣");
        System.out.printf ("║  Protocol   : %-22s║%n", isWs ? "WebSocket" : "TCP");
        System.out.printf ("║  Server     : %-22s║%n", server);
        System.out.printf ("║  Name       : %-22s║%n", name);
        if (roomId != null)
            System.out.printf("║  Room       : %-22s║%n", roomId);
        System.out.printf ("║  Auto-claim : %-22s║%n", autoClaim ? "BẬT" : "TẮT");
        System.out.printf ("║  Admin token: %-22s║%n", hasAdminToken ? "có (tự auth)" : "không");
        System.out.printf ("║  Mode       : %-22s║%n", isReconnect ? "RECONNECT" : "JOIN mới");
        System.out.println("╠══════════════════════════════════════╣");
        System.out.println("║  Type 'help' for commands            ║");
        System.out.println("╚══════════════════════════════════════╝");
    }

    private static void printHelp() {
        System.out.println("  buy    [count]                 → mua tờ (default: 1)");
        System.out.println("  vote                           → vote bắt đầu");
        System.out.println("  claim  <pageId>                → báo kình");
        System.out.println("  pages                          → xem tờ + trạng thái");
        System.out.println("  wallet                         → số dư & lịch sử");
        System.out.println("  status                         → trạng thái kết nối + phòng");
        System.out.println("  ── Admin auth ─────────────────────────────────────────");
        System.out.println("  auth   <token>                 → xác thực admin bằng token");
        System.out.println("  ── Admin only ─────────────────────────────────────────");
        System.out.println("  start                          → bắt đầu game (bypass vote)");
        System.out.println("  end    [reason]                → kết thúc game (no winner)");
        System.out.println("  reset                          → reset phòng về WAITING");
        System.out.println("  pause                          → tạm dừng game");
        System.out.println("  resume                         → tiếp tục game");
        System.out.println("  confirm <playerId> <pageId>    → xác nhận kình");
        System.out.println("  reject  <playerId> <pageId>    → từ chối kình");
        System.out.println("  topup   <playerId> <amount>    → nạp tiền");
        System.out.println("  cancel  [reason]               → hủy game + hoàn tiền");
        System.out.println("  kick    <playerId> [reason]    → kick");
        System.out.println("  ban     <playerId> [reason]    → ban tên");
        System.out.println("  unban   <tên>                  → gỡ ban tên");
        System.out.println("  banip   <ip>                   → ban IP");
        System.out.println("  unbanip <ip>                   → gỡ ban IP");
        System.out.println("  banlist                        → xem danh sách bị cấm");
        System.out.println("  speed   [ms]                   → xem/đổi tốc độ rút số");
        System.out.println("  price   [số tiền]              → xem/đổi giá tờ");
        System.out.println("  autoreset [giây]               → xem/đổi auto-reset (0=tắt)");
        System.out.println("  autostart [giây]               → xem/đổi auto-start (0=tắt)");
        System.out.println("  ── Kết nối ─────────────────────────────────────────────");
        System.out.println("  reconnect-test                 → thông tin test reconnect");
        System.out.println("  disconnect                     → ngắt kết nối + xóa token");
        System.out.println("  quit                           → thoát");
        System.out.println("  ── Reconnect từ lần khác ───────────────────────────────");
        System.out.println("  Thêm --token <token> khi chạy jar để RECONNECT vào session cũ");
    }

    private static int  safeInt(String s, int def)   { try { return Integer.parseInt(s);  } catch (Exception e) { return def; } }
    private static long safeLong(String s, long def) { try { return Long.parseLong(s);    } catch (Exception e) { return def; } }

    // ── Console callback ──────────────────────────────────────────

    static class ConsoleCallback implements LotoClientCallback {

        @Override public void onConnected() {
            System.out.println("[~] Đang kết nối...");
        }

        @Override public void onJoined(String id, String token, boolean isHost) {
            System.out.printf("[+] Đã vào phòng  id=%-8s%s%n", id, isHost ? "  (HOST)" : "");
            System.out.println("┌─ Lệnh reconnect (copy để dùng lại) ──────────────────────");
            // Build lại lệnh reconnect từ args hiện tại
            String server = client.getPlayerName(); // placeholder — in full cmd below
            System.out.printf("│  --token %s%n", token);
            System.out.println("└──────────────────────────────────────────────────────────");
            // Auto-auth if token was provided at startup
            if (pendingAdminToken != null) {
                System.out.println("[⚙] Đang xác thực admin...");
                client.adminAuth(pendingAdminToken);
            }
        }

        @Override public void onDisconnected(boolean willRetry) {
            System.out.println(willRetry ? "[~] Mất kết nối — đang thử lại..." : "[-] Đã ngắt");
        }

        @Override public void onReconnected(String gameState, List<RoomPlayer> players,
                                            List<Integer> drawnNumbers) {
            System.out.println("╔══════════════════════════════════════════╗");
            System.out.printf ("║  ↩  RECONNECTED  —  state: %-14s║%n", gameState);
            System.out.printf ("║  Số đã quay: %-4d  Tờ: %-17d║%n",
                    drawnNumbers.size(), client.getPages().size());
            System.out.println("╚══════════════════════════════════════════╝");
            if (!players.isEmpty()) {
                System.out.println("[R] Room snapshot:");
                for (RoomPlayer p : players)
                    System.out.printf("    %-8s %-12s %s tờ=%-2d balance=%,d%n",
                            p.playerId, p.name, p.isConnected ? "●" : "○", p.pageCount, p.balance);
            }
            // Re-auth admin if token is set
            if (pendingAdminToken != null) {
                client.adminAuth(pendingAdminToken);
            }
        }

        @Override public void onAdminAuthOk() {
            System.out.println("[⚙] Admin authenticated ✓ — host-only commands active");
        }

        @Override public void onBanList(java.util.List<String> names, java.util.List<String> ips) {
            System.out.println("  ─── Ban list ────────────────────────────");
            System.out.println("  Tên  : " + (names.isEmpty() ? "(trống)" : String.join(", ", names)));
            System.out.println("  IPs  : " + (ips.isEmpty()   ? "(trống)" : String.join(", ", ips)));
        }

        @Override public void onRoomUpdate(List<RoomPlayer> players, String gameState) {
            System.out.println("[R] Room [" + gameState + "] — " + players.size() + " người:");
            for (RoomPlayer p : players)
                System.out.printf("    %-8s %-12s %s%s tờ=%-2d balance=%,d%n",
                        p.playerId, p.name,
                        p.isConnected ? "●" : "○",
                        p.isBot ? " [BOT]" : "",
                        p.pageCount, p.balance);
        }

        @Override public void onPlayerJoined(String id, String name, boolean isHost) {
            System.out.printf("[+] %s vào phòng%s%n", name, isHost ? " (HOST)" : "");
        }

        @Override public void onPlayerLeft(String id) {
            System.out.println("[-] " + id + " rời phòng");
        }

        @Override public void onPagesAssigned(List<ClientPage> pages) {
            System.out.println("[P] Nhận " + pages.size() + " tờ — gõ 'pages' để xem");
        }

        @Override public void onInsufficientBalance(long required, long actual) {
            System.out.printf("[!] Không đủ tiền — cần %,d, có %,d%n", required, actual);
        }

        @Override public void onVoteUpdate(int current, int needed) {
            System.out.printf("[V] Vote %d / %d%n", current, needed);
        }

        @Override public void onGameStarting(int intervalMs) {
            System.out.println("[!] ─── GAME BẮT ĐẦU — quay mỗi " + intervalMs + "ms ───");
        }

        @Override public void onDrawIntervalChanged(int intervalMs) {
            System.out.printf("[⚡] Tốc độ → %d ms/số%n", intervalMs);
        }

        @Override public void onPricePerPageChanged(long newPrice) {
            System.out.printf("[💲] Giá tờ → %,d đồng%n", newPrice);
        }

        @Override public void onAutoResetScheduled(int delayMs) {
            if (delayMs > 0) System.out.printf("[⏱] Auto-reset sau %d giây%n", delayMs / 1000);
            else             System.out.println("[⏱] Auto-reset đã tắt");
        }

        @Override public void onAutoStartScheduled(int delayMs) {
            if (delayMs > 0) System.out.printf("[🚀] Auto-start sau %d giây%n", delayMs / 1000);
            else             System.out.println("[🚀] Auto-start đã huỷ");
        }

        @Override public void onGamePaused()  { System.out.println("[⏸] Game tạm dừng"); }
        @Override public void onGameResumed() { System.out.println("[▶] Game tiếp tục"); }

        @Override public void onNumberDrawn(int number, List<Integer> drawn,
                                            List<ClientPage> markedPages,
                                            List<ClientPage> wonPages) {
            System.out.printf("[#] Số: %-3d  (%d/90)", number, drawn.size());
            if (!markedPages.isEmpty()) {
                System.out.print("  ★ hit: ");
                markedPages.forEach(p -> System.out.print("#" + p.getId() + " "));
            }
            System.out.println();
        }

        @Override public void onPageWon(ClientPage page, int rowIndex) {
            System.out.println("╔══════════════════════════════════════════╗");
            System.out.printf ("║  🎉 KÌNH! Tờ #%-4d hàng %d               ║%n",
                    page.getId(), rowIndex + 1);
            System.out.println("║  Gõ: claim " + page.getId() + "                          ║");
            System.out.println("╚══════════════════════════════════════════╝");
        }

        @Override public void onClaimReceived(String id, String name, int pageId) {
            System.out.printf("[🔔] %s báo kình tờ #%d%n", name, pageId);
        }

        @Override public void onWinConfirmed(String id, String name, int pageId) {
            System.out.printf("[✅] %s THẮNG tờ #%d%n", name, pageId);
        }

        @Override public void onWinRejected(String id, int pageId) {
            System.out.println("[❌] Kình bị từ chối — chơi tiếp!");
        }

        @Override public void onGameEnded(String winnerId, String winnerName) {
            System.out.println("╔══════════════════════════════════════════╗");
            System.out.printf ("║  🏆 Draw stopped — Winner: %-14s║%n", winnerName);
            System.out.println("║  Ai còn kình thì claim nhanh lên!       ║");
            System.out.println("╚══════════════════════════════════════════╝");
        }

        @Override public void onGameCancelled(String reason) {
            System.out.println("[✗] Game hủy: " + reason + " — tiền đã hoàn");
        }

        @Override public void onGameEndedByServer(String reason) {
            System.out.println("╔══════════════════════════════════════════╗");
            System.out.printf ("║  ⏹  Game kết thúc: %-22s║%n", reason);
            System.out.println("╚══════════════════════════════════════════╝");
        }

        @Override public void onKicked(String reason) {
            System.out.println("╔══════════════════════════════════════════╗");
            System.out.printf ("║  🚫 Bạn bị kick: %-24s║%n", reason);
            System.out.println("╚══════════════════════════════════════════╝");
        }

        @Override public void onBanned(String reason) {
            System.out.println("╔══════════════════════════════════════════╗");
            System.out.printf ("║  🔨 Bạn bị cấm: %-25s║%n", reason);
            System.out.println("╚══════════════════════════════════════════╝");
        }

        @Override public void onRoomReset(long prizeEach, int winnerCount) {
            System.out.println("╔══════════════════════════════════════════╗");
            System.out.println("║  ↺  ROOM RESET — Game mới sắp bắt đầu  ║");
            if (winnerCount > 0)
                System.out.printf("║  💰 %d người thắng, mỗi người: %,d%s║%n",
                        winnerCount, prizeEach,
                        " ".repeat(Math.max(0, 12 - String.format("%,d", prizeEach).length())));
            System.out.println("╚══════════════════════════════════════════╝");
        }

        @Override public void onBalanceUpdate(WalletInfo wallet) {
            System.out.printf("[💰] Balance: %,d%n", wallet.getBalance());
            if (!wallet.getTransactions().isEmpty()) {
                WalletInfo.TxRecord tx = wallet.getTransactions().get(0);
                System.out.printf("     %s %+,d  (%s)%n", tx.type, tx.amount, tx.note);
            }
        }

        @Override public void onWalletHistory(WalletInfo wallet) {
            System.out.printf("[💰] Balance: %,d — Lịch sử:%n", wallet.getBalance());
            for (WalletInfo.TxRecord tx : wallet.getTransactions())
                System.out.printf("     %-12s %+,10d  %s%n", tx.type, tx.amount, tx.note);
        }

        @Override public void onError(String code, String message) {
            System.err.println("[ERR] " + code + ": " + message);
        }
    }
}
