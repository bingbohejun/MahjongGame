package doublemoon.mahjonggame.game.mahjong.riichi.model

/**
 * 所有麻将点棒, 按材质顺序排
 * */
enum class ScoringStick(
    val point: Int
) {
    P100(100),
    P1000(1000),
    P5000(5000),
    P10000(10000)
    ;

    val code = this.ordinal
}