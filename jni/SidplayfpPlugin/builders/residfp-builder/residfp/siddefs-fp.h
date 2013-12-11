//  ---------------------------------------------------------------------------
//  This file is part of reSID, a MOS6581 SID emulator engine.
//  Copyright (C) 1999  Dag Lem <resid@nimrod.no>
//
//  This program is free software; you can redistribute it and/or modify
//  it under the terms of the GNU General Public License as published by
//  the Free Software Foundation; either version 2 of the License, or
//  (at your option) any later version.
//
//  This program is distributed in the hope that it will be useful,
//  but WITHOUT ANY WARRANTY; without even the implied warranty of
//  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//  GNU General Public License for more details.
//
//  You should have received a copy of the GNU General Public License
//  along with this program; if not, write to the Free Software
//  Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
//  ---------------------------------------------------------------------------

#ifndef SIDDEFS_FP_H
#define SIDDEFS_FP_H

#ifndef M_PI
#  define M_PI    3.14159265358979323846
#endif

namespace reSIDfp {

typedef enum { MOS6581=1, MOS8580 } ChipModel;

typedef enum { DECIMATE=1, RESAMPLE } SamplingMethod;
}

extern "C"
{
#ifndef __VERSION_CC__
extern const char* residfp_version_string;
#else
const char* residfp_version_string = "1.2.0beta";
#endif
}

// Inlining on/off.
#define RESID_INLINING 1
#define RESID_INLINE inline

#endif // SIDDEFS_FP_H
