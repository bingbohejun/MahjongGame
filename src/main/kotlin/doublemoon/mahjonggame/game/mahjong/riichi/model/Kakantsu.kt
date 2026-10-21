package doublemoon.mahjonggame.game.mahjong.riichi.model

import org.mahjong4j.hands.Kantsu
import org.mahjong4j.tile.Tile


/**
 * 加杠子, 加杠专用
 * */
class Kakantsu(
    identifierTile: Tile
) : Kantsu(true, identifierTile)