package com.hinterland.clinic

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

// floors = the robot's maps (docs/robot-setup.md 2). the robot reports its maps in the live map frame
// (maps: [{ name, floor? }]) and the map it is on; places carry the map they are on (mapName, empty = any).
// otto only offers and walks to places of his current map: he cannot take the elevator. plain kotlin, unit tested.

data class FloorMap(val name: String, val floor: Int? = null)

object Floors {

    fun same(a: String?, b: String?): Boolean {
        val x = a?.trim().orEmpty()
        val y = b?.trim().orEmpty()
        return x.isNotEmpty() && x.equals(y, ignoreCase = true)
    }

    // a place is on otto's floor when it says no map, or the map otto is on. while otto does not know his map
    // yet, nothing is hidden
    fun onMap(placeMap: String?, current: String?): Boolean {
        val p = placeMap?.trim().orEmpty()
        if (p.isEmpty()) return true
        val c = current?.trim().orEmpty()
        if (c.isEmpty()) return true
        return p.equals(c, ignoreCase = true)
    }

    // RobotApi.getMultiFloorConfigAndPose: a json list of MultiFloorInfo (javap on core 1.0.20250821:
    // floorId, floorIndex, floorAlias, floorState, mapName, availableElevators, ..., poseList).
    // null = not a list (the robot has no multi-floor setup, or answered something else)
    fun fromMultiFloor(message: String?): List<FloorMap>? {
        val arr = array(message, "data", "list", "floors") ?: return null
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = str(o, "mapName", "map_name") ?: return@mapNotNull null
            FloorMap(name, int(o, "floorIndex", "floor_index", "floor"))
        }
    }

    // RobotApi.getLocalMapInfoList: not documented. a list of names, or of objects with a map name
    // (mapName / map_name / name), maybe under data / list / maps. null = nothing usable
    fun fromMapList(message: String?): List<FloorMap>? {
        val arr = array(message, "data", "list", "maps", "mapList", "mapInfoList", "map_list") ?: return null
        val out = (0 until arr.length()).mapNotNull { i ->
            when (val item = arr.opt(i)) {
                is String -> item.trim().takeIf { it.isNotEmpty() }?.let { FloorMap(it) }
                is JSONObject -> str(item, "mapName", "map_name", "name")?.let { FloorMap(it, int(item, "floorIndex", "floor")) }
                else -> null
            }
        }
        return out
    }

    // all maps otto knows of: the multi-floor setup first (it has the floors), then the local list, then at
    // least the map he is on. one entry per name, a floor from any source
    fun merge(current: String?, vararg lists: List<FloorMap>?): List<FloorMap> {
        val out = LinkedHashMap<String, FloorMap>()
        fun add(m: FloorMap) {
            val k = m.name.trim().lowercase(Locale.ROOT)
            if (k.isEmpty()) return
            val old = out[k]
            out[k] = if (old == null) m.copy(name = m.name.trim()) else old.copy(floor = old.floor ?: m.floor)
        }
        lists.forEach { it?.forEach(::add) }
        current?.trim()?.takeIf { it.isNotEmpty() }?.let { add(FloorMap(it)) }
        return out.values.toList()
    }

    private fun array(message: String?, vararg keys: String): JSONArray? {
        val t = message?.trim().orEmpty()
        if (t.isEmpty()) return null
        if (t.startsWith("[")) return runCatching { JSONArray(t) }.getOrNull()
        val o = runCatching { JSONObject(t) }.getOrNull() ?: return null
        return keys.firstNotNullOfOrNull { o.optJSONArray(it) }
    }

    private fun str(o: JSONObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k -> if (o.has(k) && !o.isNull(k)) o.optString(k).trim().takeIf { it.isNotEmpty() } else null }

    private fun int(o: JSONObject, vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { k -> if (o.has(k) && !o.isNull(k)) SettingValues.number(o.opt(k))?.toInt() else null }
}

// what to do with a switch_map command (docs/robot-setup.md 2)
sealed interface SwitchPlan {
    // not now, with the answer for the dashboard and the log
    data class Refuse(val why: String) : SwitchPlan
    // already on that map and no spot to stand at: nothing to do
    data class Already(val why: String) : SwitchPlan
    // already on that map: only tell the robot where it stands
    data class Relocalise(val spot: String) : SwitchPlan
    // switch, then tell the robot where it stands (spot null = the team did not say)
    data class Switch(val mapName: String, val spot: String?) : SwitchPlan
}

object SwitchMapRules {

    fun plan(mapName: String?, spot: String?, guiding: Boolean, current: String?): SwitchPlan {
        val map = mapName?.trim().orEmpty()
        val at = spot?.trim()?.takeIf { it.isNotEmpty() }
        if (guiding) return SwitchPlan.Refuse("Otto is walking with a patient. Switch the map after the walk.")
        if (map.isEmpty()) return SwitchPlan.Refuse("The command has no map name.")
        if (Floors.same(map, current)) {
            return if (at == null) SwitchPlan.Already("Otto is on $map already.") else SwitchPlan.Relocalise(at)
        }
        return SwitchPlan.Switch(map, at)
    }

    // the spot's pose on the map (getPlaceList after the switch), language-free name match
    fun spot(name: String, places: List<MapPlace>): MapPlace? = WalkMath.find(name, places)

    // before switching: is the spot on the new map? null = the robot did not tell (then it is checked after the switch)
    fun spotOnMap(name: String?, placesOfMap: List<MapPlace>?): Boolean? {
        val n = name?.trim().orEmpty()
        if (n.isEmpty() || placesOfMap == null || placesOfMap.isEmpty()) return null
        return spot(n, placesOfMap) != null
    }
}
