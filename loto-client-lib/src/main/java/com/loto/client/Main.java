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
 * Standalone entry point — chạy client từ terminal.
 *
 * Usage:
 *   java -jar loto-client.jar [options]
 *
 * Options:
 *   --host   <string>   Server IP (default: localhost)
 *   --port   <int>      Server port (default: 9000)
 *   --name   <string>   Player name (required)
 *   --auto-claim        Tự báo kình khi có hàng hoàn thành
 *
 * Example:
 *   java -jar loto-client.jar --host 192.168.1.10 --port 9000 --name Nguyen
 */
public class Main {

    private static LotoClient client;

    public static void main(String[] args) {
        String  host       = "localhost";
        int     port       = 9000;
        String  name       = null;
        boolean autoClaim  = false;

        // ── Parse args ────────────────────────────────────────────
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host":        host      = args[++i]; break;
                case "--port":        port      = Integer.parseInt(args[++i]); break;
                case "--name":        name      = args[++i]; break;
                case "--auto-claim":  autoClaim = true; break;
            }
        }

        if (name == null || name.trim().isEmpty()) {
            System.err.println("ERROR: --name is required");
            System.err.println("Usage: java -jar loto-client.jar --name <name> [--host <ip>] [--port <port>] [--auto-claim]");
            System.exit(1);
        }

        printBanner(host, port, name, autoClaim);

        client = new LotoClient.Builder()
                .host(host)
                .port(port)
                .playerName(name)
                .autoReconnect(true)
                .reconnectDelayMs(3_000)
                .autoClaimOnWin(autoClaim)
                .callback(new ConsoleCallback())
                .build();

        // Connect on background thread
        Thread connectThread = new Thread(client::connect, "loto-client-connect");
        connectThread.setDaemon(true);
        connectThread.start();

        // CLI command loop on main thread
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
                case "help":
                    printHelp();
                    break;

                case "buy":
                    // buy [count]
                    int count = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
                    client.buyPages(count);
                    System.out.println("→ Đang mua " + count + " tờ...");
                    break;

                case "vote":
                    client.voteStart();
                    System.out.println("→ Đã vote bắt đầu");
                    break;

                case "claim":
                    // claim <pageId>
                    if (parts.length < 2) { System.out.println("Usage: claim <pageId>"); break; }
                    client.claimWin(Integer.parseInt(parts[1]));
                    System.out.println("→ Đã báo kình tờ #" + parts[1]);
                    break;

                case "pages":
                    printPages();
                    break;

                case "wallet":
                    client.requestWalletHistory();
                    break;

                case "status":
                    printStatus();
                    break;

                // ── Host commands ──────────────────────────────
                case "confirm":
                    // confirm <playerId> <pageId>
                    if (parts.length < 3) { System.out.println("Usage: confirm <playerId> <pageId>"); break; }
                    client.confirmWin(parts[1], Integer.parseInt(parts[2]));
                    break;

                case "reject":
                    if (parts.length < 3) { System.out.println("Usage: reject <playerId> <pageId>"); break; }
                    client.rejectWin(parts[1], Integer.parseInt(parts[2]));
                    break;

                case "topup":
                    // topup <playerId> <amount> [note]
                    if (parts.length < 3) { System.out.println("Usage: topup <playerId> <amount> [note]"); break; }
                    String note = parts.length > 3
                            ? String.join(" ", Arrays.copyOfRange(parts, 3, parts.length)) : null;
                    client.topUp(parts[1], Long.parseLong(parts[2]), note);
                    break;

                case "cancel":
                    String reason = parts.length > 1
                            ? String.join(" ", Arrays.copyOfRange(parts, 1, parts.length))
                            : "Host hủy game";
                    client.cancelGame(reason);
                    break;

                case "quit": case "exit":
                    System.out.println("Đang ngắt kết nối...");
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
            System.out.println("  Chưa có tờ nào. Dùng 'buy <count>' để mua.");
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
                    if (cell == null) {
                        System.out.print("  . ");
                    } else if (page.isMarked(r, c)) {
                        System.out.printf("[%2d]", cell);
                    } else {
                        System.out.printf(" %2d ", cell);
                    }
                }
                System.out.println();
            }
            System.out.println("  └─────────────────────────────────────");
        }
    }

    private static void printStatus() {
        System.out.println("  State   : " + client.getState());
        System.out.println("  Player  : " + client.getPlayerId()
                + (client.isHost() ? " (HOST)" : ""));
        System.out.println("  Balance : " + String.format("%,d", client.getWallet().getBalance()));
        System.out.println("  Pages   : " + client.getPages().size());
        System.out.println("  Drawn   : " + client.getDrawnNumbers().size() + " / 90");
    }

    private static void printBanner(String host, int port, String name, boolean autoClaim) {
        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║           LOTO CLIENT                ║");
        System.out.println("╠══════════════════════════════════════╣");
        System.out.printf ("║  Server     : %-22s║%n", host + ":" + port);
        System.out.printf ("║  Name       : %-22s║%n", name);
        System.out.printf ("║  Auto-claim : %-22s║%n", autoClaim ? "BẬT" : "TẮT");
        System.out.println("╠══════════════════════════════════════╣");
        System.out.println("║  Type 'help' for commands            ║");
        System.out.println("╚══════════════════════════════════════╝");
    }

    private static void printHelp() {
        System.out.println("  buy [count]                    → mua tờ (default: 1)");
        System.out.println("  vote                           → vote bắt đầu game");
        System.out.println("  claim <pageId>                 → báo kình");
        System.out.println("  pages                          → xem tất cả tờ + trạng thái");
        System.out.println("  wallet                         → xem số dư & lịch sử");
        System.out.println("  status                         → xem trạng thái kết nối");
        System.out.println("  ── Host only ─────────────────────────────────────");
        System.out.println("  confirm <playerId> <pageId>    → xác nhận kình");
        System.out.println("  reject  <playerId> <pageId>    → từ chối kình");
        System.out.println("  topup   <playerId> <amount>    → nạp tiền");
        System.out.println("  cancel  [reason]               → hủy game");
        System.out.println("  quit                           → thoát");
    }

    // ── Console callback ──────────────────────────────────────────

    static class ConsoleCallback implements LotoClientCallback {

        @Override public void onConnected() {
            System.out.println("[~] Đang kết nối...");
        }

        @Override public void onJoined(String playerId, String token, boolean isHost) {
            System.out.printf("[+] Đã vào phòng  id=%-8s%s%n",
                    playerId, isHost ? "  (HOST)" : "");
            System.out.println("    Token: " + token + "  ← lưu lại để reconnect");
        }

        @Override public void onDisconnected(boolean willRetry) {
            System.out.println(willRetry
                    ? "[~] Mất kết nối — đang thử lại..."
                    : "[-] Đã ngắt kết nối");
        }

        @Override public void onReconnected() {
            System.out.println("[↩] Đã kết nối lại — state restored");
        }

        @Override public void onRoomUpdate(List<RoomPlayer> players, String gameState) {
            System.out.println("[R] Room [" + gameState + "] — " + players.size() + " người:");
            for (RoomPlayer p : players) {
                System.out.printf("    %-8s %-12s %s pages=%-2d balance=%,d%n",
                        p.playerId, p.name,
                        p.isConnected ? "●" : "○",
                        p.pageCount, p.balance);
            }
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

        @Override public void onNumberDrawn(int number, List<Integer> drawn,
                                            List<ClientPage> markedPages,
                                            List<ClientPage> wonPages) {
            System.out.printf("[#] Số: %-3d  (đã quay: %d/90)", number, drawn.size());
            if (!markedPages.isEmpty()) {
                System.out.print("  ★ hit tờ: ");
                for (ClientPage p : markedPages) System.out.print("#" + p.getId() + " ");
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
            System.out.printf ("║  🏆 GAME OVER — Winner: %-17s║%n", winnerName);
            System.out.println("╚══════════════════════════════════════════╝");
        }

        @Override public void onGameCancelled(String reason) {
            System.out.println("[✗] Game hủy: " + reason + " — tiền đã được hoàn");
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
            for (WalletInfo.TxRecord tx : wallet.getTransactions()) {
                System.out.printf("     %-12s %+,10d  %s%n", tx.type, tx.amount, tx.note);
            }
        }

        @Override public void onError(String code, String message) {
            System.err.println("[ERR] " + code + ": " + message);
        }
    }
}
