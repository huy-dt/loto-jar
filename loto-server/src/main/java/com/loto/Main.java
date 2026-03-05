package com.loto;

import com.loto.callback.LotoServerCallback;
import com.loto.core.GameRoom;
import com.loto.core.LotoServer;
import com.loto.core.ServerConfig;
import com.loto.model.LotoPage;
import com.loto.model.Player;

import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import java.util.Set;

/**
 * Standalone entry point — chạy server từ terminal.
 *
 * Usage:
 *   java -jar loto-server.jar [options]
 *
 * Options:
 *   --port              <int>   TCP port (default: 9000)
 *   --interval          <int>   Draw interval in ms (default: 5000)
 *   --reconnect-timeout <int>   Reconnect timeout in ms (default: 30000)
 *   --vote-threshold    <int>   Vote threshold % (default: 51)
 *   --max-pages         <int>   Max pages per buy (default: 10)
 *
 * Example:
 *   java -jar loto-server.jar --port 8888 --interval 3000 --vote-threshold 100
 */
public class Main {

    public static void main(String[] args) {
        ServerConfig config = parseArgs(args);

        printBanner(config);

        LotoServer server = new LotoServer.Builder()
                .config(config)
                .callback(new ConsoleCallback())
                .build();

        Thread serverThread = new Thread(server::startSafe);
        serverThread.setDaemon(true);
        serverThread.start();

        runCommandLoop(server);
    }

    // ─── Arg parsing ─────────────────────────────────────────────

    private static ServerConfig parseArgs(String[] args) {
        ServerConfig.Builder b = new ServerConfig.Builder();
        for (int i = 0; i < args.length - 1; i++) {
            try {
                switch (args[i]) {
                    case "--port":              b.port(Integer.parseInt(args[++i]));               break;
                    case "--interval":          b.drawIntervalMs(Integer.parseInt(args[++i]));     break;
                    case "--reconnect-timeout": b.reconnectTimeoutMs(Integer.parseInt(args[++i])); break;
                    case "--vote-threshold":    b.voteThresholdPct(Integer.parseInt(args[++i]));   break;
                    case "--max-pages":         b.maxPagesPerBuy(Integer.parseInt(args[++i]));     break;
                    case "--price":             b.pricePerPage(Long.parseLong(args[++i]));         break;
                    case "--initial-balance":   b.initialBalance(Long.parseLong(args[++i]));       break;
                    default:
                        System.err.println("[WARN] Unknown option: " + args[i]);
                }
            } catch (NumberFormatException e) {
                System.err.println("[WARN] Invalid value for " + args[i] + " — using default");
            }
        }
        return b.build();
    }

    // ─── Interactive CLI ──────────────────────────────────────────

    private static void runCommandLoop(LotoServer server) {
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

                case "status":
                    GameRoom room = server.getRoom();
                    System.out.printf("  State   : %s%n", room.getState());
                    System.out.printf("  Jackpot : %,d%n", room.getJackpot());
                    System.out.println("  Players :");
                    room.getRoomSnapshot().forEach(p ->
                        System.out.printf("    %-8s %-12s host=%-5s online=%-5s pages=%-3d balance=%,d%n",
                                p.playerId, p.name, p.isHost, p.isConnected, p.pageCount, p.balance));
                    break;

                case "confirm":
                    if (parts.length < 3) { System.out.println("Usage: confirm <playerId> <pageId>"); break; }
                    server.getRoom().confirmWin(parts[1], Integer.parseInt(parts[2]));
                    break;

                case "reject":
                    if (parts.length < 3) { System.out.println("Usage: reject <playerId> <pageId>"); break; }
                    server.getRoom().rejectWin(parts[1], Integer.parseInt(parts[2]));
                    break;

                case "topup":
                    // topup <playerId> <amount> [note]
                    if (parts.length < 3) { System.out.println("Usage: topup <playerId> <amount> [note]"); break; }
                    long amount = Long.parseLong(parts[2]);
                    String note = parts.length > 3 ? String.join(" ", Arrays.copyOfRange(parts, 3, parts.length)) : null;
                    server.getRoom().topUp(parts[1], amount, note);
                    break;

                case "cancel":
                    String reason = parts.length > 1
                            ? String.join(" ", Arrays.copyOfRange(parts, 1, parts.length))
                            : "Host hủy game";
                    server.getRoom().cancelGame(reason);
                    break;

                case "kick":
                    // kick <playerId> [reason]
                    if (parts.length < 2) { System.out.println("Usage: kick <playerId> [reason]"); break; }
                    String kickReason = parts.length > 2
                            ? String.join(" ", Arrays.copyOfRange(parts, 2, parts.length)) : null;
                    server.getRoom().kick(parts[1], kickReason);
                    break;

                case "ban":
                    // ban <playerId> [reason]
                    if (parts.length < 2) { System.out.println("Usage: ban <playerId> [reason]"); break; }
                    String banReason = parts.length > 2
                            ? String.join(" ", Arrays.copyOfRange(parts, 2, parts.length)) : null;
                    server.getRoom().ban(parts[1], banReason);
                    break;

                case "unban":
                    // unban <name>
                    if (parts.length < 2) { System.out.println("Usage: unban <name>"); break; }
                    server.getRoom().unban(parts[1]);
                    break;

                case "banlist":
                    Set<String> banned = server.getRoom().getBannedIds();
                    if (banned.isEmpty()) System.out.println("  (trống)");
                    else banned.forEach(n -> System.out.println("  - " + n));
                    break;

                case "quit": case "exit":
                    System.out.println("Shutting down...");
                    server.stop();
                    System.exit(0);
                    break;

                default:
                    System.out.println("Unknown command. Type 'help'.");
            }
        }
    }

    // ─── UI helpers ───────────────────────────────────────────────

    private static void printBanner(ServerConfig config) {
        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║           LOTO SERVER                ║");
        System.out.println("╠══════════════════════════════════════╣");
        System.out.printf ("║  Port              : %-16d║%n", config.port);
        System.out.printf ("║  Draw interval     : %-13dms║%n", config.drawIntervalMs);
        System.out.printf ("║  Reconnect timeout : %-13dms║%n", config.reconnectTimeoutMs);
        System.out.printf ("║  Vote threshold    : %-15d%%║%n", config.voteThresholdPct);
        System.out.printf ("║  Max pages/buy     : %-16d║%n", config.maxPagesPerBuy);
        System.out.printf ("║  Price per page    : %,-16d║%n", config.pricePerPage);
        System.out.printf ("║  Initial balance   : %,-16d║%n", config.initialBalance);
        System.out.println("╠══════════════════════════════════════╣");
        System.out.println("║  Type 'help' for commands            ║");
        System.out.println("╚══════════════════════════════════════╝");
    }

    private static void printHelp() {
        System.out.println("  status                             → show players, jackpot, balances");
        System.out.println("  topup  <playerId> <amount> [note]  → nạp tiền cho player");
        System.out.println("  confirm <playerId> <pageId>        → xác nhận kình");
        System.out.println("  reject  <playerId> <pageId>        → từ chối kình");
        System.out.println("  cancel [reason]                    → hủy game, hoàn tiền");
        System.out.println("  kick   <playerId> [reason]         → kick player");
        System.out.println("  ban    <playerId> [reason]         → kick + cấm tái join");
        System.out.println("  unban  <name>                      → gỡ cấm");
        System.out.println("  banlist                            → xem danh sách bị cấm");
        System.out.println("  quit                               → stop server");
    }

    // ─── Console callback ─────────────────────────────────────────

    static class ConsoleCallback implements LotoServerCallback {

        @Override public void onPlayerJoined(Player p) {
            System.out.printf("[+] Joined      id=%-8s name=%s%s%n",
                    p.getId(), p.getName(), p.isHost() ? " (HOST)" : "");
        }

        @Override public void onPlayerLeft(Player p, boolean permanent) {
            System.out.printf("[%s] Left        id=%-8s name=%s%n",
                    permanent ? "✗" : "~", p.getId(), p.getName());
        }

        @Override public void onPlayerReconnected(Player p) {
            System.out.printf("[↩] Reconnected id=%-8s name=%s%n", p.getId(), p.getName());
        }

        @Override public void onPagesBought(Player p, List<LotoPage> pages) {
            System.out.printf("[P] Pages bought  player=%-10s count=%-2d total=%-3d balance=%,d%n",
                    p.getName(), pages.size(), p.getPages().size(), p.getBalance());
        }

        @Override public void onInsufficientBalance(Player p, long required, long actual) {
            System.out.printf("[!] Insufficient  player=%-10s required=%,d have=%,d%n",
                    p.getName(), required, actual);
        }

        @Override public void onVoteUpdate(int count, int needed) {
            System.out.printf("[V] Vote %d / %d%n", count, needed);
        }

        @Override public void onGameStarting() {
            System.out.println("[!] ─────── GAME STARTING ───────────────");
        }

        @Override public void onNumberDrawn(int number, List<Integer> drawn) {
            System.out.printf("[#] Drew: %-3d  (drawn: %d / 90)%n", number, drawn.size());
        }

        @Override public void onClaimReceived(Player p, int pageId) {
            System.out.println("╔══════════════════════════════════════════╗");
            System.out.printf ("║  🎉 KÌNH!  player=%-10s pageId=%-4d  ║%n", p.getName(), pageId);
            System.out.println("╠══════════════════════════════════════════╣");
            System.out.printf ("║  confirm %-8s %-4d                    ║%n", p.getId(), pageId);
            System.out.printf ("║  reject  %-8s %-4d                    ║%n", p.getId(), pageId);
            System.out.println("╚══════════════════════════════════════════╝");
        }

        @Override public void onWinConfirmed(Player p, int pageId, long jackpot) {
            System.out.printf("[W] CONFIRMED   player=%s pageId=%d jackpot=%,d%n",
                    p.getName(), pageId, jackpot);
        }

        @Override public void onWinRejected(Player p, int pageId) {
            System.out.printf("[X] Rejected    player=%s pageId=%d%n", p.getName(), pageId);
        }

        @Override public void onGameEnded(Player winner, long jackpot) {
            System.out.println("╔══════════════════════════════════════════╗");
            System.out.printf ("║  🏆 GAME OVER — Winner: %-17s║%n", winner.getName());
            System.out.printf ("║  💰 Jackpot  : %,d%-17s║%n", jackpot, "");
            System.out.println("╚══════════════════════════════════════════╝");
        }

        @Override public void onTopUp(Player p, long amount) {
            System.out.printf("[💰] Top-up    player=%-10s amount=%,d  balance=%,d%n",
                    p.getName(), amount, p.getBalance());
        }

        @Override public void onGameCancelled(String reason, long totalRefunded) {
            System.out.printf("[✗] Game cancelled: %s — refunded %,d total%n", reason, totalRefunded);
        }

        @Override public void onPlayerKicked(Player p, String reason) {
            System.out.printf("[🚫] Kicked  player=%-10s reason=%s%n",
                    p.getName(), reason != null ? reason : "—");
        }

        @Override public void onPlayerBanned(String playerId, String reason) {
            System.out.printf("[⛔] Banned  id=%-10s reason=%s%n",
                    playerId, reason != null ? reason : "—");
        }

        @Override public void onPlayerUnbanned(String playerId) {
            System.out.printf("[✓] Unbanned id=%s%n", playerId);
        }

        @Override public void onServerError(String msg) {
            System.err.println("[ERR] " + msg);
        }
    }
}
