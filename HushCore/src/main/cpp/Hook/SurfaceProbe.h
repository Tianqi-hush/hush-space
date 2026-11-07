//
// SurfaceProbe.h
//

#ifndef HUSHCORE_SURFACE_PROBE_H
#define HUSHCORE_SURFACE_PROBE_H

namespace SurfaceProbe {

/** 安装 EGL / ANativeWindow 观测。幂等，只在壳白名单下调用。 */
bool install();

}  // namespace SurfaceProbe

#endif  // HUSHCORE_SURFACE_PROBE_H
