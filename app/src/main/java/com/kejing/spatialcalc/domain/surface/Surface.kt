package com.kejing.spatialcalc.domain.surface

import com.kejing.spatialcalc.domain.math.Vec3

/** What a detected plane is, from its semantic label (空间尺 SurfaceKind, cut down to what the calculator uses). */
enum class SurfaceKind(val zh: String, val en: String) {
    FLOOR("地面", "floor"), TABLE("桌面", "table"), WALL("墙", "wall"), CEILING("天花板", "ceiling"),
    CABINET("柜子", "cabinet"), OTHER("平面", "surface"), VIRTUAL("虚拟桌高", "virtual table");

    val horizontal: Boolean get() = this == FLOOR || this == TABLE || this == CEILING || this == VIRTUAL

    companion object {
        fun fromSemanticName(name: String): SurfaceKind {
            val n = name.uppercase()
            return when {
                "FLOOR" in n -> FLOOR
                "TABLE" in n || "DESK" in n -> TABLE
                "WALL" in n || "DOOR" in n || "WINDOW" in n -> WALL
                "CEILING" in n -> CEILING
                "CABINET" in n || "SHELF" in n || "BED" in n || "SOFA" in n || "SEAT" in n -> CABINET
                else -> OTHER
            }
        }
    }
}
data class PlaneSurface(
    val id: String,
    val kind: SurfaceKind,
    val semanticName: String,
    val vertices: List<Vec3>,
    val indices: List<Int>,
) {
    /** Unit normal of the first non-degenerate triangle; its sign is arbitrary. */
    val normal: Vec3 by lazy {
        var n = Vec3.UP
        var i = 0
        while (i + 2 < indices.size) {
            val v0 = vertices[indices[i]]
            val c = (vertices[indices[i + 1]] - v0).cross(vertices[indices[i + 2]] - v0)
            if (c.length > 1e-9f) {
                n = c.normalized()
                break
            }
            i += 3
        }
        n
    }

    val centroid: Vec3 by lazy {
        if (vertices.isEmpty()) Vec3.ZERO
        else vertices.fold(Vec3.ZERO) { acc, v -> acc + v } / vertices.size.toFloat()
    }

    /** `SEMANTIC:uuid8`, the name used for the plane's collider entity and in logs. */
    val label: String
        get() = "$semanticName:${id.take(8)}"

    /** Boundary of the polygon as closed segments (vertex order as reported). */
    val edges: List<Pair<Vec3, Vec3>>
        get() = vertices.indices.map { vertices[it] to vertices[(it + 1) % vertices.size] }
}

/** Result of a ray / surface intersection. [normal] faces the ray origin. */
data class SurfaceHit(val point: Vec3, val normal: Vec3, val distance: Float, val plane: PlaneSurface)

/** A pointing ray in Stage space. Every aiming channel (system tap, debug ray) ends here. */
data class PointerRay(val origin: Vec3, val direction: Vec3)
