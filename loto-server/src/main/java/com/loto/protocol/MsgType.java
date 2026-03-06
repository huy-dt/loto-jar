package com.loto.protocol;

public enum MsgType {
    // ── Inbound (Client → Server) ──────────────────────────────────
    JOIN,
    RECONNECT,
    BUY_PAGE,
    VOTE_START,
    CLAIM_WIN,
    CONFIRM_WIN,    // host only
    REJECT_WIN,     // host only
    TOPUP,          // host only — nạp tiền cho player
    CANCEL_GAME,    // host only — hủy game, hoàn tiền
    GET_WALLET,     // player xem lịch sử giao dịch
    KICK,           // host only — kick player
    BAN,            // host only — ban player
    UNBAN,          // host only — gỡ ban
    SET_DRAW_INTERVAL,  // host only — đổi tốc độ rút số realtime
    SET_PRICE_PER_PAGE, // host only — đổi giá tiền cược mỗi tờ (khi chưa có ai mua)
    SET_AUTO_RESET,     // host only — đổi/tắt auto-reset delay (bất kỳ lúc nào)

    // ── Outbound (Server → Client) ─────────────────────────────────
    WELCOME,
    ROOM_UPDATE,
    PLAYER_JOINED,
    PLAYER_LEFT,
    PAGES_ASSIGNED,
    VOTE_UPDATE,
    GAME_STARTING,
    NUMBER_DRAWN,
    CLAIM_RECEIVED,
    WIN_CONFIRMED,
    WIN_REJECTED,
    GAME_ENDED,
    BALANCE_UPDATE,
    WALLET_HISTORY,
    GAME_CANCELLED,
    KICKED,         // gửi riêng cho player bị kick trước khi đóng kết nối
    BANNED,         // gửi cho player bị ban khi cố join
    ROOM_RESET,     // server reset phòng về WAITING
    DRAW_INTERVAL_CHANGED,  // server thay đổi tốc độ rút số
    PRICE_PER_PAGE_CHANGED, // server thay đổi giá cược mỗi tờ
    AUTO_RESET_SCHEDULED,   // server thông báo sẽ tự reset sau xx giây
    ERROR
}
