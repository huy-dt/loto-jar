package com.loto;

import com.loto.callback.LotoServerCallback;
import com.loto.core.GameRoom;
import com.loto.core.LotoServer;
import com.loto.core.ServerConfig;
import com.loto.model.BotPlayer;
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
 *   java -jar loto-server.jar [transport] [options]
 *
 * Transport (chọn 1, default: --both):
 *   --tcp  [port]              TCP only  (default port: 9000)
 *   --ws   [wsPort]            WS only   (default port: 9001)
 *   --both [tcpPort] [wsPort]  TCP + WS  (default: 9000 + 9001)
 *
 * Options:
 *   --port              <int>   TCP port (default: 9000)
 *   --ws-port           <int>   WebSocket port (default: tcpPort+1)
 *   --interval          <int>   Draw interval in ms (default: 5000)
 *   --reconnect-timeout <int>   Reconnect timeout in ms (default: 30000)
 *   --vote-threshold    <int>   Vote threshold % (default: 51)
 *   --max-pages         <int>   Max pages per buy (default: 10)
 *   --price             <long>  Price per page (default: 10000)
 *   --initial-balance   <long>  Starting balance per player (default: 0)
 *   --persist           <path>  JSON save file path (default: off)
 *   --min-players       <int>   Min players to allow start (default: 1)
 *   --auto-reset        <int>   Auto-reset delay in ms after game ends (default: 0 = off)
 *
 * Examples:
 *   java -jar loto-server.jar --tcp 9000
 *   java -jar loto-server.jar --ws 9001
 *   java -jar loto-server.jar --both 9000 9001 --price 5000 --persist save.json
 */
public class Main {

    public static void main(String[] args) {
        ServerConfig.Builder configBuilder = parseArgsToBuilder(args);
        ServerConfig config = configBuilder.build();

        printBanner(config);

        LotoServer server = new LotoServer.Builder()
                .config(config)
                .transportMode(config.transportMode)
                .callback(new ConsoleCallback())
                .build();

        server.loadSavedState();

        Thread serverThread = new Thread(server::startSafe);
        serverThread.setDaemon(true);
        serverThread.start();

        runCommandLoop(server);
    }

    // ─── Arg parsing ─────────────────────────────────────────────

    private static ServerConfig.Builder parseArgsToBuilder(String[] args) {
        ServerConfig.Builder b = new ServerConfig.Builder();
        for (int i = 0; i < args.length; i++) {
            try {
                switch (args[i]) {
                    // ── Existing options ──────────────────────────
                    case "--port":              b.port(Integer.parseInt(args[++i]));               break;
                    case "--interval":          b.drawIntervalMs(Integer.parseInt(args[++i]));     break;
                    case "--reconnect-timeout": b.reconnectTimeoutMs(Integer.parseInt(args[++i])); break;
                    case "--vote-threshold":    b.voteThresholdPct(Integer.parseInt(args[++i]));   break;
                    case "--max-pages":         b.maxPagesPerBuy(Integer.parseInt(args[++i]));     break;
                    case "--price":             b.pricePerPage(Long.parseLong(args[++i]));         break;
                    case "--initial-balance":   b.initialBalance(Long.parseLong(args[++i]));       break;
                    case "--persist":           b.persistPath(args[++i]);                         break;
                    case "--min-players":       b.minPlayers(Integer.parseInt(args[++i]));         break;
                    case "--auto-verify":       b.autoVerifyWin(true);                           break;
                    case "--auto-reset":        b.autoResetDelayMs(Integer.parseInt(args[++i])); break;
                    case "--auto-start":        b.autoStartMs(Integer.parseInt(args[++i]));      break;
                    case "--admin-token":       b.adminToken(args[++i]);                        break;

                    // ── Transport flags ───────────────────────────
                    // --tcp [port]          TCP only, optional port override
                    case "--tcp": {
                        if (i + 1 < args.length && args[i+1].matches("\\d+"))
                            b.port(Integer.parseInt(args[++i]));
                        b.transportMode(com.loto.core.TransportMode.TCP);
                        break;
                    }
                    // --ws [port]           WebSocket only, optional WS port
                    case "--ws": {
                        if (i + 1 < args.length && args[i+1].matches("\\d+"))
                            b.wsPort(Integer.parseInt(args[++i]));
                        b.transportMode(com.loto.core.TransportMode.WS);
                        break;
                    }
                    // --both [tcpPort] [wsPort]   Both transports, optional ports
                    case "--both": {
                        if (i + 1 < args.length && args[i+1].matches("\\d+"))
                            b.port(Integer.parseInt(args[++i]));
                        if (i + 1 < args.length && args[i+1].matches("\\d+"))
                            b.wsPort(Integer.parseInt(args[++i]));
                        b.transportMode(com.loto.core.TransportMode.BOTH);
                        break;
                    }

                    // Legacy --ws-port still works for explicit WS port config
                    case "--ws-port":
                        b.wsPort(Integer.parseInt(args[++i]));
                        break;

                    default:
                        if (args[i].startsWith("--"))
                            System.err.println("[WARN] Unknown option: " + args[i]);
                }
            } catch (ArrayIndexOutOfBoundsException e) {
                System.err.println("[WARN] Missing value for " + args[i]);
            } catch (NumberFormatException e) {
                System.err.println("[WARN] Invalid number for " + args[i]);
            }
        }
        return b;
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

                case "start":
                    server.getRoom().serverStart();
                    System.out.println("  [→] Game started by server.");
                    break;

                case "pause":
                    server.getRoom().pauseGame();
                    break;

                case "resume":
                    server.getRoom().resumeGame();
                    break;

                case "end":
                    String endReason = parts.length > 1
                            ? String.join(" ", Arrays.copyOfRange(parts, 1, parts.length))
                            : "Server kết thúc game";
                    server.getRoom().serverEnd(endReason);
                    System.out.println("  [→] Game ended by server.");
                    break;

                case "speed":
                    // speed <ms>   — change draw interval live
                    // speed        — show current interval
                    if (parts.length < 2) {
                        System.out.printf("  Current draw interval: %d ms%n",
                                server.getRoom().getCurrentDrawIntervalMs());
                        break;
                    }
                    try {
                        int ms = Integer.parseInt(parts[1]);
                        server.getRoom().setDrawInterval(ms);
                        System.out.printf("  [⚡] Draw interval → %d ms%n", ms);
                    } catch (NumberFormatException e) {
                        System.out.println("  Usage: speed <ms>  (e.g. speed 2000)");
                    }
                    break;

                case "price":
                    // price          — show current price
                    // price <amount> — set price per page (only when no pages bought yet)
                    if (parts.length < 2) {
                        System.out.printf("  Giá hiện tại: %,d đ/tờ%s%n",
                                server.getRoom().getCurrentPricePerPage(),
                                server.getRoom().canChangePricePerPage() ? "" : "  [LOCKED - đã có người mua]");
                        break;
                    }
                    if (!server.getRoom().canChangePricePerPage()) {
                        System.out.println("  [!] Không thể đổi giá — đã có người mua tờ.");
                        break;
                    }
                    try {
                        long newPrice = Long.parseLong(parts[1]);
                        if (newPrice < 0) { System.out.println("  [!] Giá phải >= 0"); break; }
                        server.getRoom().setPricePerPage(newPrice);
                        System.out.printf("  [💲] Giá cược → %,d đ/tờ%n", newPrice);
                    } catch (NumberFormatException e) {
                        System.out.println("  Usage: price <số tiền>  (e.g. price 20000)");
                    }
                    break;

                case "autoreset":
                    // autoreset        — show current delay
                    // autoreset <sec>  — set auto-reset delay in seconds (0 = disable)
                    if (parts.length < 2) {
                        int cur = server.getRoom().getCurrentAutoResetDelayMs();
                        if (cur == 0) System.out.println("  Auto-reset: TẮT");
                        else          System.out.printf("  Auto-reset: %d giây sau khi game kết thúc%n", cur / 1000);
                        break;
                    }
                    try {
                        int sec = Integer.parseInt(parts[1]);
                        if (sec < 0) { System.out.println("  [!] Số giây phải >= 0 (0 = tắt)"); break; }
                        server.getRoom().setAutoResetDelay(sec * 1000);
                        if (sec == 0) System.out.println("  [⏱] Auto-reset đã TẮT");
                        else          System.out.printf("  [⏱] Auto-reset → %d giây%n", sec);
                    } catch (NumberFormatException e) {
                        System.out.println("  Usage: autoreset <giây>  (e.g. autoreset 30)  |  autoreset 0 = tắt");
                    }
                    break;

                case "autostart":
                    // autostart        — show current delay
                    // autostart <sec>  — set auto-start delay in seconds (0 = disable)
                    if (parts.length < 2) {
                        int cur = server.getRoom().getCurrentAutoStartMs();
                        if (cur == 0) System.out.println("  Auto-start: TẮT");
                        else          System.out.printf("  Auto-start: %d giây sau khi đủ %d người%n",
                                cur / 1000, server.getRoom().getConfig().minPlayers);
                        break;
                    }
                    try {
                        int sec = Integer.parseInt(parts[1]);
                        if (sec < 0) { System.out.println("  [!] Số giây phải >= 0 (0 = tắt)"); break; }
                        server.getRoom().setAutoStartMs(sec * 1000);
                        if (sec == 0) System.out.println("  [🚀] Auto-start đã TẮT");
                        else          System.out.printf("  [🚀] Auto-start → %d giây sau khi đủ %d người%n",
                                sec, server.getRoom().getConfig().minPlayers);
                    } catch (NumberFormatException e) {
                        System.out.println("  Usage: autostart <giây>  (e.g. autostart 10)  |  autostart 0 = tắt");
                    }
                    break;

                case "reset":
                    server.getRoom().reset();
                    System.out.println("  [↺] Room reset. Jackpot đã chia, tờ cũ xóa, balance giữ nguyên.");
                    break;

                case "bot":
                    // bot add <name> [maxPages] [balance]
                    // bot remove <name>
                    // bot list
                    if (parts.length < 2) {
                        System.out.println("  Usage: bot add <name> [maxPages=3] [balance=999999]");
                        System.out.println("         bot remove <name>");
                        System.out.println("         bot list");
                        break;
                    }
                    switch (parts[1].toLowerCase()) {
                        case "add": {
                            if (parts.length < 3) {
                                System.out.println("  Usage: bot add <name> [maxPages=3] [balance=999999]");
                                break;
                            }
                            String botName   = parts[2];
                            int    maxPages  = parts.length > 3 ? Integer.parseInt(parts[3]) : 3;
                            long   botBal    = parts.length > 4 ? Long.parseLong(parts[4])   : 999_999L;
                            BotPlayer bot =
                                    server.getRoom().getBotManager().addBot(botName, botBal, maxPages);
                            if (bot == null)
                                System.out.println("  [!] Bot '" + botName + "' đã tồn tại hoặc không thể thêm.");
                            else
                                System.out.printf("  [🤖] Bot '%s' đã vào phòng (maxPages=%d, balance=%,d)%n",
                                        botName, maxPages, botBal);
                            break;
                        }
                        case "remove": {
                            if (parts.length < 3) { System.out.println("  Usage: bot remove <name>"); break; }
                            boolean removed = server.getRoom().getBotManager().removeBot(parts[2]);
                            System.out.println(removed
                                    ? "  [🤖] Bot '" + parts[2] + "' đã rời phòng."
                                    : "  [!] Không tìm thấy bot '" + parts[2] + "'.");
                            break;
                        }
                        case "list": {
                            var botList = server.getRoom().getBotManager().listBots();
                            if (botList.isEmpty()) {
                                System.out.println("  (Không có bot nào)");
                            } else {
                                System.out.println("  ─── Bots ───────────────────────────────");
                                for (var b : botList) {
                                    System.out.printf("  🤖 %-12s  maxPages=%-3d  balance=%,d  tờ=%d%n",
                                            b.getName(), b.getMaxPages(),
                                            b.getBalance(), b.getPages().size());
                                }
                            }
                            break;
                        }
                        default:
                            System.out.println("  Usage: bot add|remove|list");
                    }
                    break;

                case "banip":
                    // banip <ip>
                    if (parts.length < 2) { System.out.println("Usage: banip <ip>"); break; }
                    server.getRoom().banIp(parts[1]);
                    System.out.println("  [⛔] Banned IP: " + parts[1]);
                    break;

                case "unbanip":
                    if (parts.length < 2) { System.out.println("Usage: unbanip <ip>"); break; }
                    server.getRoom().unbanIp(parts[1]);
                    System.out.println("  [✓] Unbanned IP: " + parts[1]);
                    break;

                case "banlist":
                    Set<String> banned = server.getRoom().getBannedIds();
                    Set<String> bannedIps = server.getRoom().getBannedIps();
                    System.out.println("  Names: " + (banned.isEmpty() ? "(trống)" : String.join(", ", banned)));
                    System.out.println("  IPs:   " + (bannedIps.isEmpty() ? "(trống)" : String.join(", ", bannedIps)));
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
        String transportLine;
        switch (config.transportMode) {
            case TCP:  transportLine = String.format("TCP only  (port %d)", config.port);          break;
            case WS:   transportLine = String.format("WS only   (port %d)", config.wsPort);        break;
            default:   transportLine = String.format("TCP:%d + WS:%d", config.port, config.wsPort); break;
        }
        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║           LOTO SERVER                ║");
        System.out.println("╠══════════════════════════════════════╣");
        System.out.printf ("║  Transport         : %-16s║%n", transportLine);
        System.out.printf ("║  Draw interval     : %-13dms║%n", config.drawIntervalMs);
        System.out.printf ("║  Reconnect timeout : %-13dms║%n", config.reconnectTimeoutMs);
        System.out.printf ("║  Vote threshold    : %-15d%%║%n", config.voteThresholdPct);
        System.out.printf ("║  Max pages/buy     : %-16d║%n", config.maxPagesPerBuy);
        System.out.printf ("║  Price per page    : %,-16d║%n", config.pricePerPage);
        System.out.printf ("║  Initial balance   : %,-16d║%n", config.initialBalance);
        System.out.printf ("║  Persist path      : %-16s║%n", config.persistPath != null ? config.persistPath : "off");
        System.out.printf ("║  Min players       : %-16d║%n", config.minPlayers);
        System.out.printf ("║  Auto-verify win   : %-16s║%n", config.autoVerifyWin ? "ON" : "OFF");
        String arLabel = config.autoResetDelayMs > 0 ? config.autoResetDelayMs/1000 + "s" : "off";
        System.out.printf ("║  Auto-reset delay  : %-16s║%n", arLabel);
        String asLabel = config.autoStartMs > 0 ? config.autoStartMs/1000 + "s after minPlayers" : "off";
        System.out.printf ("║  Auto-start delay  : %-16s║%n", asLabel);
        System.out.println("╠══════════════════════════════════════╣");
        System.out.printf ("║  ⚠  ADMIN TOKEN (keep secret!)       ║%n");
        System.out.printf ("║  %s║%n", fitToken(config.adminToken));
        System.out.println("╠══════════════════════════════════════╣");
        System.out.println("║  Type 'help' for commands            ║");
        System.out.println("╚══════════════════════════════════════╝");
    }

    /** Pads or truncates token to exactly 36 chars for banner display. */
    private static String fitToken(String token) {
        if (token == null) token = "";
        if (token.length() > 36) token = token.substring(0, 36);
        return String.format("%-36s", token);
    }

    private static void printHelp() {
        System.out.println("  status                             → show players, jackpot, balances");
        System.out.println("  start                              → server bắt đầu game (bypass vote)");
        System.out.println("  pause                              → tạm dừng game (dừng rút số)");
        System.out.println("  resume                             → tiếp tục game sau khi pause");
        System.out.println("  end    [reason]                    → server kết thúc game (no winner)");
        System.out.println("  reset                              → reset phòng về WAITING (giữ balance)");
        System.out.println("  speed  [ms]                        → xem / đổi tốc độ rút số (live, min 200ms)");
        System.out.println("  price  [amount]                    → xem / đổi giá cược/tờ (được khi chưa ai mua)");
        System.out.println("  autoreset [giây]                   → xem / đặt tự reset sau N giây (0 = tắt)");
        System.out.println("  autostart [giây]                   → xem / đặt tự start sau N giây khi đủ minPlayers (0 = tắt)");
        System.out.println("  bot add <n> [maxTờ] [balance]    → thêm bot (mặc định: maxTờ=3, balance=999999)");
        System.out.println("  bot remove <n>                     → xóa bot");
        System.out.println("  bot list                           → xem danh sách bot");
        System.out.println("  topup  <playerId> <amount> [note]  → nạp tiền cho player");
        System.out.println("  confirm <playerId> <pageId>        → xác nhận kình");
        System.out.println("  reject  <playerId> <pageId>        → từ chối kình");
        System.out.println("  cancel [reason]                    → hủy game, hoàn tiền");
        System.out.println("  kick   <playerId> [reason]         → kick player");
        System.out.println("  ban    <playerId> [reason]         → kick + cấm tái join");
        System.out.println("  unban  <name>                      → gỡ cấm");
        System.out.println("  banlist                            → xem tên + IP bị cấm");
        System.out.println("  banip  <ip>                        → cấm theo IP");
        System.out.println("  unbanip <ip>                       → gỡ cấm IP");
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

        @Override public void onServerGameEnded(String reason) {
            System.out.printf("[→] Game ended by server: %s%n", reason);
        }

        @Override public void onGamePaused() {
            System.out.println("[⏸] Game PAUSED.");
        }

        @Override public void onGameResumed() {
            System.out.println("[▶] Game RESUMED.");
        }

        @Override public void onRoomReset() {
            System.out.println("[↺] Room reset — WAITING for new game.");
        }

        @Override public void onDrawIntervalChanged(int oldMs, int newMs) {
            System.out.printf("[⚡] Draw interval: %dms → %dms%n", oldMs, newMs);
        }

        @Override public void onPricePerPageChanged(long oldPrice, long newPrice) {
            System.out.printf("[💲] Giá cược: %,d → %,d đ/tờ%n", oldPrice, newPrice);
        }

        @Override public void onAutoResetScheduled(int delayMs) {
            System.out.printf("[⏱] Auto-reset sau %d giây%n", delayMs / 1000);
        }

        @Override public void onAutoResetDelayChanged(int oldMs, int newMs) {
            if (newMs == 0) System.out.println("[⏱] Auto-reset đã tắt");
            else System.out.printf("[⏱] Auto-reset delay: %ds → %ds%n", oldMs/1000, newMs/1000);
        }

        @Override public void onAutoStartScheduled(int delayMs) {
            if (delayMs == 0) System.out.println("[🚀] Auto-start đã huỷ");
            else System.out.printf("[🚀] Auto-start sau %d giây (đủ minPlayers)%n", delayMs / 1000);
        }

        @Override public void onAutoStartMsChanged(int oldMs, int newMs) {
            if (newMs == 0) System.out.println("[🚀] Auto-start đã tắt");
            else System.out.printf("[🚀] Auto-start delay: %ds → %ds%n", oldMs/1000, newMs/1000);
        }

        @Override public void onJackpotPaid(java.util.List<String> winnerIds, long prizeEach) {
            System.out.println("╔══════════════════════════════════════════╗");
            System.out.printf ("║  💰 JACKPOT PAID — %d người thắng        ║%n", winnerIds.size());
            System.out.printf ("║  Mỗi người nhận: %,d%-14s║%n", prizeEach, "");
            System.out.println("╚══════════════════════════════════════════╝");
        }
    }
}
