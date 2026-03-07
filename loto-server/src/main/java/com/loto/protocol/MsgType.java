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
    SET_AUTO_START,     // admin only — đổi/tắt auto-start delay (ms sau khi đủ minPlayers)
    ADMIN_AUTH,         // xác thực admin bằng token bí mật
    PAUSE_GAME,         // admin only — tạm dừng game (dừng rút số)
    RESUME_GAME,        // admin only — tiếp tục game sau khi pause
    SERVER_START,       // admin only — bắt đầu game ngay (bypass vote)
    SERVER_END,         // admin only — kết thúc game không có winner
    RESET_ROOM,         // admin only — reset phòng về WAITING (giữ balance)
    BAN_IP,             // admin only — cấm theo địa chỉ IP
    UNBAN_IP,           // admin only — gỡ cấm IP
    GET_BAN_LIST,       // admin only — lấy danh sách tên + IP bị cấm

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
    AUTO_START_SCHEDULED,   // server thông báo sẽ tự start sau xx giây (0 = huỷ)
    ADMIN_AUTH_OK,          // xác thực admin thành công
    GAME_PAUSED,            // server thông báo game đang tạm dừng
    GAME_RESUMED,           // server thông báo game tiếp tục
    BAN_LIST,               // trả về danh sách tên + IP bị cấm (chỉ admin)
    ERROR
}
