package doublemoon.mahjonggame.game.mahjong.riichi.model

import doublemoon.mahjonggame.network.mahjong_table.MahjongTablePayloadListener

/**
 * 麻将桌行为, 发送数据包 [MahjongTablePayloadListener] 用
 * */
enum class MahjongTableBehavior {
    //c2s
    JOIN,
    LEAVE,
    READY,
    NOT_READY,
    START,
    KICK,
    ADD_BOT,
    CHANGE_RULE,
    REQUEST_END, //请求结束游戏 (投票)

    //s2c
    OPEN_TABLE_WAITING_GUI,
    OPEN_TABLE_PLAYING_GUI,

    //both
    OPEN_RULES_EDITOR_GUI,
}
