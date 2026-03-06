package com.loto.protocol;

public enum MsgType {
    // ── Inbound (Client → Server) ──────────────────────────────────
    JOIN,
    RECONNECT,
    BUY_PAGE,
    VOTE_START,
    CLAIM_WIN,
    CONFIRM_WIN,    // admin only
    REJECT_WIN,     // admin only
    TOPUP,          // admin only — nạp tiền cho player
    CANCEL_GAME,    // admin only — hủy game, hoàn tiền
    GET_WALLET,     // player xem lịch sử giao dịch
    KICK,           // admin only — kick player
    BAN,            // admin only — ban player
    UNBAN,          // admin only — gỡ ban
    SET_DRAW_INTERVAL,  // admin only — đổi tốc độ rút số realtime
    SET_PRICE_PER_PAGE, // admin only — đổi giá tiền cược mỗi tờ (khi chưa có ai mua)
    SET_AUTO_RESET,     // admin only — đổi/tắt auto-reset delay (bất kỳ lúc nào)
    ADMIN_AUTH,         // xác thực admin bằng token bí mật
    PAUSE_GAME,         // admin only — tạm dừng game (dừng rút số)
    RESUME_GAME,        // admin only — tiếp tục game sau khi pause

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
    ADMIN_AUTH_OK,          // xác thực admin thành công
    GAME_PAUSED,            // server thông báo game đang tạm dừng
    GAME_RESUMED,           // server thông báo game tiếp tục
    ERROR
}
