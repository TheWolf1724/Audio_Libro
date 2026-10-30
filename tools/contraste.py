#!/usr/bin/env python3
"""
LectorVoz - generador y verificador de temas (sin dependencias, solo biblioteca estandar).

Que hace:
  1. Construye las paletas tonales de cada tema en CIELAB/LCh(ab) (tono = L*, igual
     que el "tone" de Material 3 / HCT; el matiz y croma son de LCh(ab), no de CAM16,
     por lo que los valores no coinciden al hex con Material Theme Builder).
  2. Asigna los roles Material 3 (ColorScheme completo) y los roles de lectura.
  3. Verifica TODOS los pares declarados con la formula de contraste WCAG 2.x
     (luminancia relativa, sRGB, umbral 0.04045).
  4. Simula daltonismo (Machado, Oliveira y Fernandes 2009, severidad 1.0:
     protanopia, deuteranopia, tritanopia) y mide la distancia CIEDE2000 entre
     resaltados que pueden coexistir en pantalla.
  5. Escribe:
       docs/diseno/temas.json            tokens en formato maquina
       docs/diseno/TEMAS.md              (solo el bloque entre marcadores SALIDA-SCRIPT)
       docs/diseno/muestra-temas.html    (solo el bloque CSS entre marcadores TOKENS)
     y vuelca el informe por la salida estandar.

Uso:  python3 tools/contraste.py            (genera y verifica; codigo de salida 1 si algo falla)
      python3 tools/contraste.py --solo-informe   (no escribe ficheros)
"""
import json
import math
import os
import re
import sys

RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIR_DISENO = os.path.join(RAIZ, "docs", "diseno")

# ---------------------------------------------------------------------------
# 1. Colorimetria basica
# ---------------------------------------------------------------------------

def hex_a_rgb(h):
    h = h.lstrip("#")
    if len(h) == 8:          # AARRGGBB -> se ignora alfa
        h = h[2:]
    return tuple(int(h[i:i + 2], 16) / 255.0 for i in (0, 2, 4))


def rgb_a_hex(rgb):
    return "#" + "".join("%02X" % max(0, min(255, int(round(c * 255)))) for c in rgb)


def lineal(c):
    # sRGB -> lineal. WCAG 2.2 (nota de 2021) usa 0.04045, como IEC 61966-2-1.
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def gamma(c):
    return 12.92 * c if c <= 0.0031308 else 1.055 * (c ** (1 / 2.4)) - 0.055


def luminancia(hexc):
    r, g, b = (lineal(c) for c in hex_a_rgb(hexc))
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contraste(a, b):
    la, lb = luminancia(a), luminancia(b)
    if la < lb:
        la, lb = lb, la
    return (la + 0.05) / (lb + 0.05)


M_RGB_XYZ = ((0.4124564, 0.3575761, 0.1804375),
             (0.2126729, 0.7151522, 0.0721750),
             (0.0193339, 0.1191920, 0.9503041))
M_XYZ_RGB = ((3.2404542, -1.5371385, -0.4985314),
             (-0.9692660, 1.8760108, 0.0415560),
             (0.0556434, -0.2040259, 1.0572252))
BLANCO = (0.95047, 1.0, 1.08883)  # D65


def mul(m, v):
    return tuple(sum(m[i][j] * v[j] for j in range(3)) for i in range(3))


def _f(t):
    return t ** (1 / 3) if t > 216 / 24389 else (24389 / 27 * t + 16) / 116


def _finv(t):
    return t ** 3 if t ** 3 > 216 / 24389 else (116 * t - 16) / (24389 / 27)


def rgblin_a_lab(rl):
    x, y, z = mul(M_RGB_XYZ, rl)
    fx, fy, fz = _f(x / BLANCO[0]), _f(y / BLANCO[1]), _f(z / BLANCO[2])
    return (116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))


def hex_a_lab(hexc):
    return rgblin_a_lab(tuple(lineal(c) for c in hex_a_rgb(hexc)))


def lab_a_rgblin(L, a, b):
    fy = (L + 16) / 116
    fx, fz = fy + a / 500, fy - b / 200
    xyz = (_finv(fx) * BLANCO[0], _finv(fy) * BLANCO[1], _finv(fz) * BLANCO[2])
    return mul(M_XYZ_RGB, xyz)


def lch(L, C, h):
    """Color sRGB con L* dado; si no cabe en gamut reduce el croma (busqueda binaria)."""
    def prueba(c):
        a, b = c * math.cos(math.radians(h)), c * math.sin(math.radians(h))
        rl = lab_a_rgblin(L, a, b)
        return rl, all(-1e-6 <= v <= 1 + 1e-6 for v in rl)
    rl, ok = prueba(C)
    if not ok:
        lo, hi = 0.0, C
        for _ in range(40):
            mid = (lo + hi) / 2
            if prueba(mid)[1]:
                lo = mid
            else:
                hi = mid
        rl, _ = prueba(lo)
    return rgb_a_hex(tuple(gamma(max(0.0, min(1.0, v))) for v in rl))


def ciede2000(lab1, lab2):
    L1, a1, b1 = lab1
    L2, a2, b2 = lab2
    C1, C2 = math.hypot(a1, b1), math.hypot(a2, b2)
    Cb = (C1 + C2) / 2
    G = 0.5 * (1 - math.sqrt(Cb ** 7 / (Cb ** 7 + 25 ** 7)))
    a1p, a2p = (1 + G) * a1, (1 + G) * a2
    C1p, C2p = math.hypot(a1p, b1), math.hypot(a2p, b2)
    h1p = math.degrees(math.atan2(b1, a1p)) % 360 if C1p else 0.0
    h2p = math.degrees(math.atan2(b2, a2p)) % 360 if C2p else 0.0
    dLp, dCp = L2 - L1, C2p - C1p
    if C1p * C2p == 0:
        dhp = 0.0
    else:
        d = h2p - h1p
        dhp = d if abs(d) <= 180 else (d - 360 if d > 180 else d + 360)
    dHp = 2 * math.sqrt(C1p * C2p) * math.sin(math.radians(dhp / 2))
    Lbp, Cbp = (L1 + L2) / 2, (C1p + C2p) / 2
    if C1p * C2p == 0:
        hbp = h1p + h2p
    elif abs(h1p - h2p) <= 180:
        hbp = (h1p + h2p) / 2
    elif h1p + h2p < 360:
        hbp = (h1p + h2p + 360) / 2
    else:
        hbp = (h1p + h2p - 360) / 2
    T = (1 - 0.17 * math.cos(math.radians(hbp - 30)) + 0.24 * math.cos(math.radians(2 * hbp))
         + 0.32 * math.cos(math.radians(3 * hbp + 6)) - 0.20 * math.cos(math.radians(4 * hbp - 63)))
    dth = 30 * math.exp(-(((hbp - 275) / 25) ** 2))
    Rc = 2 * math.sqrt(Cbp ** 7 / (Cbp ** 7 + 25 ** 7))
    Sl = 1 + 0.015 * (Lbp - 50) ** 2 / math.sqrt(20 + (Lbp - 50) ** 2)
    Sc = 1 + 0.045 * Cbp
    Sh = 1 + 0.015 * Cbp * T
    Rt = -math.sin(math.radians(2 * dth)) * Rc
    return math.sqrt((dLp / Sl) ** 2 + (dCp / Sc) ** 2 + (dHp / Sh) ** 2
                     + Rt * (dCp / Sc) * (dHp / Sh))


# Machado, Oliveira, Fernandes (2009), "A Physiologically-based Model for Simulation of
# Color Vision Deficiency", IEEE TVCG 15(6). Matrices de severidad 1.0, en RGB lineal.
# (Coinciden con colour-science, colour/blindness/datasets/machado2010.py.)
MACHADO = {
    "protanopia": ((0.152286, 1.052583, -0.204868),
                   (0.114503, 0.786281, 0.099216),
                   (-0.003882, -0.048116, 1.051998)),
    "deuteranopia": ((0.367322, 0.860646, -0.227968),
                     (0.280085, 0.672501, 0.047413),
                     (-0.011820, 0.042940, 0.968881)),
    "tritanopia": ((1.255528, -0.076749, -0.178779),
                   (-0.078411, 0.930809, 0.147602),
                   (0.004733, 0.691367, 0.303900)),
}


def simular(hexc, tipo):
    if tipo == "normal":
        return hexc
    rl = mul(MACHADO[tipo], tuple(lineal(c) for c in hex_a_rgb(hexc)))
    return rgb_a_hex(tuple(gamma(max(0.0, min(1.0, v))) for v in rl))


def autotest():
    # Par de prueba de Sharma, Wu y Dalal (2005), tabla 1, fila 1: dE00 = 2.0425
    d = ciede2000((50.0, 2.6772, -79.7751), (50.0, 0.0, -82.7485))
    assert abs(d - 2.0425) < 1e-3, d
    # Fila 17: dE00 = 27.1492
    d = ciede2000((50.0, 2.5, 0.0), (73.0, 25.0, -18.0))
    assert abs(d - 27.1492) < 1e-3, d
    assert abs(contraste("#000000", "#FFFFFF") - 21.0) < 1e-9
    assert abs(contraste("#777777", "#FFFFFF") - 4.48) < 0.01   # valor de referencia conocido
    L, _, _ = hex_a_lab("#FFFFFF")
    assert abs(L - 100) < 0.01


# ---------------------------------------------------------------------------
# 2. Definicion de temas
# ---------------------------------------------------------------------------
# Paletas tonales: (matiz LCh(ab) en grados, croma). Un rol se define como
#   ("P", tono) -> paleta P al tono dado       ("lch", L, C, h) -> color libre
#   "#RRGGBB"   -> hex fijo

TONOS_CLARO = {
    "primary": ("P", 40), "onPrimary": ("P", 100), "primaryContainer": ("P", 90),
    "onPrimaryContainer": ("P", 10), "inversePrimary": ("P", 80),
    "secondary": ("S", 40), "onSecondary": ("S", 100), "secondaryContainer": ("S", 90),
    "onSecondaryContainer": ("S", 10),
    "tertiary": ("T", 40), "onTertiary": ("T", 100), "tertiaryContainer": ("T", 90),
    "onTertiaryContainer": ("T", 10),
    "error": ("E", 40), "onError": ("E", 100), "errorContainer": ("E", 90),
    "onErrorContainer": ("E", 10),
    "background": ("N", 98), "onBackground": ("N", 10),
    "surface": ("N", 98), "onSurface": ("N", 10),
    "surfaceVariant": ("NV", 90), "onSurfaceVariant": ("NV", 30),
    "surfaceTint": ("P", 40),
    "inverseSurface": ("N", 20), "inverseOnSurface": ("N", 95),
    "outline": ("NV", 50), "outlineVariant": ("NV", 80), "scrim": "#000000",
    "surfaceBright": ("N", 98), "surfaceDim": ("N", 87),
    "surfaceContainerLowest": ("N", 100), "surfaceContainerLow": ("N", 96),
    "surfaceContainer": ("N", 94), "surfaceContainerHigh": ("N", 92),
    "surfaceContainerHighest": ("N", 90),
}
TONOS_OSCURO = {
    "primary": ("P", 80), "onPrimary": ("P", 20), "primaryContainer": ("P", 30),
    "onPrimaryContainer": ("P", 90), "inversePrimary": ("P", 40),
    "secondary": ("S", 80), "onSecondary": ("S", 20), "secondaryContainer": ("S", 30),
    "onSecondaryContainer": ("S", 90),
    "tertiary": ("T", 80), "onTertiary": ("T", 20), "tertiaryContainer": ("T", 30),
    "onTertiaryContainer": ("T", 90),
    "error": ("E", 80), "onError": ("E", 20), "errorContainer": ("E", 30),
    "onErrorContainer": ("E", 90),
    "background": ("N", 6), "onBackground": ("N", 90),
    "surface": ("N", 6), "onSurface": ("N", 90),
    "surfaceVariant": ("NV", 30), "onSurfaceVariant": ("NV", 80),
    "surfaceTint": ("P", 80),
    "inverseSurface": ("N", 90), "inverseOnSurface": ("N", 20),
    "outline": ("NV", 60), "outlineVariant": ("NV", 30), "scrim": "#000000",
    "surfaceBright": ("N", 24), "surfaceDim": ("N", 6),
    "surfaceContainerLowest": ("N", 4), "surfaceContainerLow": ("N", 10),
    "surfaceContainer": ("N", 12), "surfaceContainerHigh": ("N", 17),
    "surfaceContainerHighest": ("N", 22),
}
FIJOS = {  # roles "fixed" de M3 (iguales en claro y oscuro)
    "primaryFixed": ("P", 90), "primaryFixedDim": ("P", 80), "onPrimaryFixed": ("P", 10),
    "onPrimaryFixedVariant": ("P", 30),
    "secondaryFixed": ("S", 90), "secondaryFixedDim": ("S", 80), "onSecondaryFixed": ("S", 10),
    "onSecondaryFixedVariant": ("S", 30),
    "tertiaryFixed": ("T", 90), "tertiaryFixedDim": ("T", 80), "onTertiaryFixed": ("T", 10),
    "onTertiaryFixedVariant": ("T", 30),
}

TEMAS = {
    # ------------------------------------------------------------------ CLARO
    "claro": {
        "nombre": "Claro", "oscuro": False, "variante": None,
        "descripcion": "Papel blanco roto apenas calido, tinta casi negra. Lectura diurna.",
        "paletas": {"P": (285, 48), "S": (280, 14), "T": (200, 26), "E": (35, 62),
                    "N": (85, 2.5), "NV": (85, 6)},
        "roles": dict(TONOS_CLARO, **FIJOS),
        "lectura": {
            "readerBackground": ("lch", 98.5, 2.5, 85),
            "readerText": ("lch", 12, 2, 85),
            "readerSecondaryText": ("lch", 33, 4, 85),
            "readerLink": ("P", 33),
            "readerLinkVisited": ("lch", 33, 40, 330),
            "ttsSentenceHighlight": ("lch", 91, 14, 265),
            "ttsSentenceIndicator": ("lch", 48, 45, 265),
            "ttsWordHighlight": ("lch", 85, 62, 92),
            "ttsWordText": ("lch", 12, 2, 85),
            "ttsWordIndicator": ("lch", 38, 45, 70),
            "selection": ("lch", 80, 26, 300),
            "selectionHandle": ("P", 40),
            "searchHighlight": ("lch", 82, 40, 50),
            "searchOutline": ("lch", 45, 60, 40),
            "searchCurrentHighlight": ("lch", 70, 62, 55),
            "searchCurrentText": ("lch", 12, 2, 85),
            "readerPageBack": ("lch", 94, 3, 85),
            "readerBarBackground": ("N", 94),
            "readerBarContent": ("N", 10),
            "readerProgressTrack": ("NV", 80),
            "readerProgressIndicator": ("P", 40),
            "readerDivider": ("NV", 80),
            "pageEdgeShadow": "#1F000000",
        },
    },
    # ------------------------------------------------------------------ OSCURO
    "oscuro": {
        "nombre": "Oscuro", "oscuro": True, "variante": None,
        "descripcion": "Gris carbon calido (no negro) con texto gris claro, sin blanco puro, "
                       "para evitar halos y fatiga.",
        "paletas": {"P": (285, 48), "S": (280, 14), "T": (200, 26), "E": (35, 62),
                    "N": (85, 2.5), "NV": (85, 6)},
        "roles": dict(TONOS_OSCURO, **FIJOS),
        "lectura": {
            "readerBackground": ("lch", 8, 2, 85),
            "readerText": ("lch", 88, 3, 85),
            "readerSecondaryText": ("lch", 72, 4, 85),
            "readerLink": ("P", 78),
            "readerLinkVisited": ("lch", 78, 30, 330),
            "ttsSentenceHighlight": ("lch", 23, 18, 265),
            "ttsSentenceIndicator": ("lch", 66, 40, 265),
            "ttsWordHighlight": ("lch", 80, 58, 88),
            "ttsWordText": ("lch", 8, 2, 85),
            "ttsWordIndicator": ("lch", 35, 45, 70),
            "selection": ("lch", 30, 34, 320),
            "selectionHandle": ("P", 80),
            "searchHighlight": ("lch", 27, 26, 55),
            "searchOutline": ("lch", 68, 55, 50),
            "searchCurrentHighlight": ("lch", 72, 55, 55),
            "searchCurrentText": ("lch", 8, 2, 85),
            "readerPageBack": ("lch", 14, 2, 85),
            "readerBarBackground": ("N", 12),
            "readerBarContent": ("N", 90),
            "readerProgressTrack": ("NV", 30),
            "readerProgressIndicator": ("P", 80),
            "readerDivider": ("NV", 30),
            "pageEdgeShadow": "#66000000",
        },
    },
}

# Variante AMOLED: igual que oscuro pero con negro puro y texto algo mas tenue
_amoled_roles = dict(TEMAS["oscuro"]["roles"])
_amoled_roles.update({
    "background": "#000000", "surface": "#000000", "surfaceDim": "#000000",
    "surfaceContainerLowest": "#000000", "surfaceContainerLow": ("N", 6),
    "surfaceContainer": ("N", 9), "surfaceContainerHigh": ("N", 13),
    "surfaceContainerHighest": ("N", 18), "surfaceBright": ("N", 20),
})
_amoled_lect = dict(TEMAS["oscuro"]["lectura"])
_amoled_lect.update({
    "readerBackground": "#000000",
    "readerText": ("lch", 85, 3, 85),
    "readerSecondaryText": ("lch", 68, 4, 85),
    "ttsSentenceHighlight": ("lch", 20, 18, 265),
    "ttsWordText": "#000000",
    "searchCurrentText": "#000000",
    "searchHighlight": ("lch", 24, 26, 55),
    "selection": ("lch", 27, 34, 320),
    "readerPageBack": ("lch", 8, 2, 85),
    "readerBarBackground": ("N", 6),
    "pageEdgeShadow": "#99000000",
})
TEMAS["oscuroAmoled"] = {
    "nombre": "Oscuro AMOLED", "oscuro": True, "variante": "oscuro",
    "descripcion": "Variante opcional del tema oscuro con negro puro (#000000) para pantallas "
                   "OLED: apaga pixeles y ahorra bateria. Texto atenuado para limitar halos.",
    "paletas": TEMAS["oscuro"]["paletas"],
    "roles": _amoled_roles,
    "lectura": _amoled_lect,
}

# ------------------------------------------------------------- PROTECCION OCULAR
_prot_roles = dict(TONOS_CLARO, **FIJOS)
_prot_roles.update({
    "primary": ("P", 35), "onPrimary": ("P", 97), "primaryContainer": ("P", 78),
    "onPrimaryContainer": ("P", 12), "inversePrimary": ("P", 78), "surfaceTint": ("P", 35),
    "secondary": ("S", 35), "onSecondary": ("S", 97), "secondaryContainer": ("S", 78),
    "onSecondaryContainer": ("S", 12),
    "tertiary": ("T", 35), "onTertiary": ("T", 97), "tertiaryContainer": ("T", 78),
    "onTertiaryContainer": ("T", 12),
    "error": ("E", 35), "onError": ("E", 97), "errorContainer": ("E", 80),
    "onErrorContainer": ("E", 12),
    "background": ("N", 88), "onBackground": ("N", 12),
    "surface": ("N", 88), "onSurface": ("N", 12),
    "surfaceVariant": ("NV", 80), "onSurfaceVariant": ("NV", 26),
    "inverseSurface": ("N", 22), "inverseOnSurface": ("N", 88),
    "outline": ("NV", 42), "outlineVariant": ("NV", 72),
    "surfaceBright": ("N", 91), "surfaceDim": ("N", 79),
    "surfaceContainerLowest": ("N", 92), "surfaceContainerLow": ("N", 90),
    "surfaceContainer": ("N", 86), "surfaceContainerHigh": ("N", 84),
    "surfaceContainerHighest": ("N", 82),
})
TEMAS["proteccionOcular"] = {
    "nombre": "Proteccion ocular", "oscuro": False, "variante": None,
    "descripcion": "Papel sepia suave de brillo moderado (L* 88), tinta marron muy oscura. "
                   "Sin azules en la interfaz; resaltados calidos/oliva.",
    "paletas": {"P": (48, 40), "S": (60, 16), "T": (110, 30), "E": (30, 58),
                "N": (80, 13), "NV": (75, 17)},
    "roles": _prot_roles,
    "lectura": {
        "readerBackground": ("lch", 88, 13, 80),
        "readerText": ("lch", 14, 10, 60),
        "readerSecondaryText": ("lch", 27, 12, 60),
        "readerLink": ("lch", 27, 45, 40),
        "readerLinkVisited": ("lch", 27, 30, 10),
        "ttsSentenceHighlight": ("lch", 83, 22, 72),
        "ttsSentenceIndicator": ("lch", 40, 45, 55),
        "ttsWordHighlight": ("lch", 65, 66, 84),
        "ttsWordText": ("lch", 3, 4, 60),
        "ttsWordIndicator": ("lch", 30, 40, 50),
        "selection": ("lch", 74, 14, 285),
        "selectionHandle": ("P", 35),
        "searchHighlight": ("lch", 71, 38, 20),
        "searchOutline": ("lch", 38, 55, 30),
        "searchCurrentHighlight": ("lch", 62, 50, 35),
        "searchCurrentText": ("lch", 8, 8, 60),
        "readerPageBack": ("lch", 82, 13, 80),
        "readerBarBackground": ("N", 86),
        "readerBarContent": ("N", 12),
        "readerProgressTrack": ("NV", 72),
        "readerProgressIndicator": ("P", 35),
        "readerDivider": ("NV", 72),
        "pageEdgeShadow": "#29301C00",
    },
}

# Variante nocturna: ambar tenue sobre marron casi negro (sin azul apreciable)
_noche_roles = dict(TONOS_OSCURO, **FIJOS)
_noche_roles.update({
    "primary": ("P", 72), "onPrimary": ("P", 15), "primaryContainer": ("P", 28),
    "onPrimaryContainer": ("P", 85), "inversePrimary": ("P", 35), "surfaceTint": ("P", 72),
    "secondary": ("S", 72), "onSecondary": ("S", 15), "secondaryContainer": ("S", 28),
    "onSecondaryContainer": ("S", 85),
    "tertiary": ("T", 72), "onTertiary": ("T", 15), "tertiaryContainer": ("T", 28),
    "onTertiaryContainer": ("T", 85),
    "error": ("E", 70), "onError": ("E", 15), "errorContainer": ("E", 28),
    "onErrorContainer": ("E", 85),
    "background": ("N", 7), "onBackground": ("N", 80),
    "surface": ("N", 7), "onSurface": ("N", 80),
    "surfaceVariant": ("NV", 28), "onSurfaceVariant": ("NV", 72),
    "inverseSurface": ("N", 80), "inverseOnSurface": ("N", 15),
    "outline": ("NV", 55), "outlineVariant": ("NV", 28),
    "surfaceBright": ("N", 22), "surfaceDim": ("N", 7),
    "surfaceContainerLowest": ("N", 4), "surfaceContainerLow": ("N", 10),
    "surfaceContainer": ("N", 12), "surfaceContainerHigh": ("N", 16),
    "surfaceContainerHighest": ("N", 20),
})
TEMAS["proteccionOcularNoche"] = {
    "nombre": "Proteccion ocular noche", "oscuro": True, "variante": "proteccionOcular",
    "descripcion": "Variante nocturna opcional: texto ambar tenue sobre marron casi negro. "
                   "Minima luz azul y minimo brillo para leer a oscuras.",
    "paletas": {"P": (70, 50), "S": (65, 18), "T": (110, 30), "E": (35, 55),
                "N": (62, 8), "NV": (65, 16)},
    "roles": _noche_roles,
    "lectura": {
        "readerBackground": ("lch", 7, 5, 60),
        "readerText": ("lch", 78, 34, 76),
        "readerSecondaryText": ("lch", 68, 28, 72),
        "readerLink": ("lch", 74, 45, 55),
        "readerLinkVisited": ("lch", 70, 30, 30),
        "ttsSentenceHighlight": ("lch", 18, 18, 60),
        "ttsSentenceIndicator": ("lch", 60, 50, 65),
        "ttsWordHighlight": ("lch", 70, 58, 76),
        "ttsWordText": ("lch", 7, 5, 60),
        "ttsWordIndicator": ("lch", 32, 40, 55),
        "selection": ("lch", 26, 14, 285),
        "selectionHandle": ("P", 72),
        "searchHighlight": ("lch", 30, 30, 20),
        "searchOutline": ("lch", 64, 50, 35),
        "searchCurrentHighlight": ("lch", 62, 50, 45),
        "searchCurrentText": ("lch", 7, 5, 60),
        "readerPageBack": ("lch", 12, 5, 60),
        "readerBarBackground": ("N", 12),
        "readerBarContent": ("N", 80),
        "readerProgressTrack": ("NV", 28),
        "readerProgressIndicator": ("P", 72),
        "readerDivider": ("NV", 28),
        "pageEdgeShadow": "#80000000",
    },
}

# Capa "aumento de contraste": se aplica encima de cualquier tema.
ALTO_CONTRASTE = {
    False: {  # temas claros
        "roles": {"onSurfaceVariant": ("NV", 15), "outline": ("NV", 30),
                  "outlineVariant": ("NV", 45), "primary": ("P", 25),
                  "surfaceTint": ("P", 25), "secondary": ("S", 25),
                  "tertiary": ("T", 25), "error": ("E", 25)},
        "lectura": {"readerText": "#000000", "ttsWordText": "#000000",
                    "readerSecondaryText": ("lch", 18, 4, 85), "readerLink": ("P", 22),
                    "ttsSentenceIndicator": ("lch", 30, 50, 265),
                    "readerProgressTrack": ("NV", 45), "readerDivider": ("NV", 45)},
    },
    True: {  # temas oscuros
        "roles": {"onSurfaceVariant": ("NV", 92), "outline": ("NV", 75),
                  "outlineVariant": ("NV", 55), "primary": ("P", 88),
                  "surfaceTint": ("P", 88), "secondary": ("S", 88),
                  "tertiary": ("T", 88), "error": ("E", 88)},
        "lectura": {"readerText": ("lch", 97, 2, 85),
                    "readerSecondaryText": ("lch", 88, 4, 85), "readerLink": ("P", 88),
                    "ttsSentenceIndicator": ("lch", 78, 40, 265),
                    "readerProgressTrack": ("NV", 55), "readerDivider": ("NV", 55)},
    },
}
# En los temas calidos el alto contraste conserva el matiz calido
ALTO_CONTRASTE_CALIDO = {
    "proteccionOcular": {"lectura": {"readerText": ("lch", 5, 6, 60),
                                     "ttsWordText": ("lch", 5, 6, 60),
                                     "readerSecondaryText": ("lch", 16, 10, 60),
                                     "readerLink": ("lch", 18, 45, 40),
                                     "ttsSentenceIndicator": ("lch", 28, 45, 55)}},
    "proteccionOcularNoche": {"lectura": {"readerText": ("lch", 90, 30, 80),
                                          "readerSecondaryText": ("lch", 82, 28, 76),
                                          "readerLink": ("lch", 85, 40, 60),
                                          "ttsSentenceIndicator": ("lch", 75, 50, 65)}},
}


def resolver(spec, paletas):
    if isinstance(spec, str):
        return spec.upper()
    if spec[0] == "lch":
        return lch(spec[1], spec[2], spec[3])
    h, c = paletas[spec[0]]
    return lch(spec[1], c, h)


def construir(nombre, alto=False):
    t = TEMAS[nombre]
    roles = dict(t["roles"])
    lect = dict(t["lectura"])
    if alto:
        capa = ALTO_CONTRASTE[t["oscuro"]]
        roles.update(capa["roles"])
        lect.update(capa["lectura"])
        if nombre in ALTO_CONTRASTE_CALIDO:
            lect.update(ALTO_CONTRASTE_CALIDO[nombre]["lectura"])
        if nombre == "oscuroAmoled":
            lect["ttsWordText"] = "#000000"
    m3 = {k: resolver(v, t["paletas"]) for k, v in roles.items()}
    rl = {k: resolver(v, t["paletas"]) for k, v in lect.items()}
    return m3, rl


# ---------------------------------------------------------------------------
# 3. Pares a verificar
# ---------------------------------------------------------------------------
# (texto/primer plano, fondo, minimo exigido, objetivo, descripcion)
# Minimos: 4.5 texto normal (AA, 1.4.3); 3.0 componentes no textuales (1.4.11);
# 7.0 texto de lectura (AAA, 1.4.6) cuando es objetivo de diseno.

PARES_M3 = [
    ("onPrimary", "primary", 4.5, 7.0, "texto sobre boton relleno"),
    ("onPrimaryContainer", "primaryContainer", 4.5, 7.0, "texto en contenedor primario"),
    ("onSecondary", "secondary", 4.5, 7.0, "texto sobre secundario"),
    ("onSecondaryContainer", "secondaryContainer", 4.5, 7.0, "chips/tonal button"),
    ("onTertiary", "tertiary", 4.5, 7.0, "texto sobre terciario"),
    ("onTertiaryContainer", "tertiaryContainer", 4.5, 7.0, "contenedor terciario"),
    ("onError", "error", 4.5, 7.0, "texto sobre error"),
    ("onErrorContainer", "errorContainer", 4.5, 7.0, "mensaje de error"),
    ("onBackground", "background", 4.5, 7.0, "texto sobre fondo"),
    ("onSurface", "surface", 4.5, 7.0, "texto sobre superficie"),
    ("onSurface", "surfaceContainerLowest", 4.5, 7.0, "texto en contenedor minimo"),
    ("onSurface", "surfaceContainerLow", 4.5, 7.0, "texto en tarjetas"),
    ("onSurface", "surfaceContainer", 4.5, 7.0, "texto en barras/menus"),
    ("onSurface", "surfaceContainerHigh", 4.5, 7.0, "texto en dialogos"),
    ("onSurface", "surfaceContainerHighest", 4.5, 7.0, "texto en campos"),
    ("onSurface", "surfaceBright", 4.5, 7.0, "texto en surfaceBright"),
    ("onSurface", "surfaceDim", 4.5, 7.0, "texto en surfaceDim"),
    ("onSurfaceVariant", "surface", 4.5, 7.0, "texto secundario/iconos"),
    ("onSurfaceVariant", "surfaceVariant", 4.5, 7.0, "texto en surfaceVariant"),
    ("onSurfaceVariant", "surfaceContainerHighest", 4.5, 7.0, "etiqueta de campo"),
    ("inverseOnSurface", "inverseSurface", 4.5, 7.0, "snackbar"),
    ("inversePrimary", "inverseSurface", 4.5, 7.0, "accion de snackbar"),
    ("primary", "surface", 4.5, 7.0, "boton de texto / enlace UI"),
    ("primary", "surfaceContainer", 4.5, 7.0, "icono activo en barra"),
    ("primary", "surfaceContainerHighest", 3.0, 4.5, "indicador/switch activo"),
    ("error", "surface", 4.5, 7.0, "texto de error"),
    ("outline", "surface", 3.0, 4.5, "borde de campo/boton (1.4.11)"),
    ("outline", "surfaceContainer", 3.0, 4.5, "borde sobre barra"),
    ("outline", "surfaceContainerHighest", 3.0, 4.5, "borde sobre contenedor alto"),
    ("onPrimaryFixed", "primaryFixed", 4.5, 7.0, "fixed"),
    ("onPrimaryFixedVariant", "primaryFixed", 4.5, 7.0, "fixed variante"),
    ("onSecondaryFixed", "secondaryFixed", 4.5, 7.0, "fixed"),
    ("onTertiaryFixed", "tertiaryFixed", 4.5, 7.0, "fixed"),
]
PARES_LECTURA = [
    ("readerText", "readerBackground", 7.0, 7.0, "texto de cuerpo"),
    ("readerSecondaryText", "readerBackground", 7.0, 7.0, "notas, cabeceras, numero de pagina"),
    ("readerLink", "readerBackground", 7.0, 7.0, "enlace (siempre subrayado)"),
    ("readerLinkVisited", "readerBackground", 7.0, 7.0, "enlace visitado (subrayado)"),
    ("readerText", "ttsSentenceHighlight", 4.5, 7.0, "frase leida en voz alta"),
    ("readerLink", "ttsSentenceHighlight", 4.5, 7.0, "enlace dentro de la frase leida"),
    ("readerSecondaryText", "ttsSentenceHighlight", 4.5, 7.0, "texto secundario en frase leida"),
    ("ttsWordText", "ttsWordHighlight", 4.5, 7.0, "palabra que se pronuncia"),
    ("readerText", "selection", 4.5, 7.0, "texto seleccionado"),
    ("readerText", "searchHighlight", 4.5, 7.0, "coincidencia de busqueda"),
    ("searchCurrentText", "searchCurrentHighlight", 4.5, 7.0, "coincidencia actual"),
    ("readerText", "readerPageBack", 4.5, 7.0, "reverso de pagina (animacion curl)"),
    ("ttsSentenceIndicator", "readerBackground", 3.0, 3.0, "barra lateral/subrayado de frase"),
    ("ttsSentenceIndicator", "ttsSentenceHighlight", 3.0, 3.0, "indicador sobre la frase"),
    ("ttsWordIndicator", "ttsWordHighlight", 3.0, 3.0, "subrayado grueso de palabra"),
    ("searchOutline", "readerBackground", 3.0, 3.0, "contorno de coincidencia"),
    ("searchOutline", "searchHighlight", 3.0, 3.0, "contorno sobre su relleno"),
    ("selectionHandle", "readerBackground", 3.0, 3.0, "tiradores de seleccion"),
    ("readerBarContent", "readerBarBackground", 4.5, 7.0, "texto/iconos de barras"),
    ("readerProgressIndicator", "readerBarBackground", 3.0, 3.0, "progreso de lectura"),
]

# Resaltados que pueden coexistir: distancia CIEDE2000 minima exigida en vision normal
# y simulada. Ademas cada uno lleva una senal no cromatica (ver TEMAS.md).
PARES_DISTINCION = [
    ("readerBackground", "ttsSentenceHighlight", 5.0, "fondo / frase (+ barra lateral)"),
    ("readerBackground", "ttsWordHighlight", 15.0, "fondo / palabra"),
    ("ttsSentenceHighlight", "ttsWordHighlight", 15.0, "frase / palabra"),
    ("readerBackground", "selection", 10.0, "fondo / seleccion (+ tiradores)"),
    ("readerBackground", "searchHighlight", 10.0, "fondo / busqueda (+ contorno)"),
    ("ttsSentenceHighlight", "selection", 5.0, "frase / seleccion"),
    ("ttsSentenceHighlight", "searchHighlight", 5.0, "frase / busqueda"),
    ("selection", "searchHighlight", 5.0, "seleccion / busqueda"),
    ("searchHighlight", "searchCurrentHighlight", 8.0, "busqueda / busqueda actual"),
    ("readerText", "readerLink", 0.0, "texto / enlace (informativo: enlace va subrayado)"),
]
PARES_DISTINCION_UI = [
    ("primary", "error", 0.0, "estado activo / error (informativo: error lleva icono y texto)"),
    ("primary", "outline", 0.0, "activo / inactivo (informativo: cambia forma y relleno)"),
]
VISIONES = ["normal", "protanopia", "deuteranopia", "tritanopia"]


# ---------------------------------------------------------------------------
# 4. Evaluacion e informe
# ---------------------------------------------------------------------------

def evaluar(nombre, alto=False):
    m3, rl = construir(nombre, alto)
    todo = dict(m3, **rl)
    filas, fallos = [], []
    for grupo, pares in (("M3", PARES_M3), ("Lectura", PARES_LECTURA)):
        for fg, bg, minimo, objetivo, desc in pares:
            r = contraste(todo[fg], todo[bg])
            if r < minimo:
                estado = "FALLO"
            else:
                estado = "AAA" if r >= 7 else ("AA" if r >= 4.5 else "3:1")
            if estado == "FALLO":
                fallos.append("%s%s: %s/%s = %.2f < %.1f" % (
                    nombre, " (alto contraste)" if alto else "", fg, bg, r, minimo))
            filas.append((grupo, fg, todo[fg], bg, todo[bg], r, minimo, objetivo, estado, desc))
    dist = []
    for a, b, minimo, desc in PARES_DISTINCION + PARES_DISTINCION_UI:
        valores = []
        for v in VISIONES:
            d = ciede2000(hex_a_lab(simular(todo[a], v)), hex_a_lab(simular(todo[b], v)))
            valores.append(d)
            if d < minimo:
                fallos.append("%s%s: dE00 %s/%s en %s = %.1f < %.1f" % (
                    nombre, " (alto contraste)" if alto else "", a, b, v, d, minimo))
        dist.append((a, b, minimo, valores, desc))
    return m3, rl, filas, dist, fallos


def proxy_azul(hexc):
    r, g, b = (lineal(c) for c in hex_a_rgb(hexc))
    return b, (b / (r + g + b) if r + g + b else 0.0)


def informe():
    out = []
    p = out.append
    todos_fallos = []
    datos = {}
    p("Generado por `tools/contraste.py` (no editar a mano este bloque; se regenera).")
    p("")
    p("Formula: contraste WCAG 2.x = (L1+0.05)/(L2+0.05), luminancia relativa sRGB "
      "(umbral 0.04045). Daltonismo: Machado et al. 2009, severidad 1.0, en RGB lineal. "
      "Distancias: CIEDE2000. Nivel: `AAA` >= 7:1, `AA` >= 4.5:1, `3:1` >= 3:1 "
      "(solo valido en pares no textuales), `FALLO` por debajo del minimo exigido al par.")
    p("")
    # Tablas de tokens: una columna por tema
    constr = {n: construir(n) for n in TEMAS}
    cab = "| Rol | " + " | ".join(TEMAS[n]["nombre"] for n in TEMAS) + " |"
    sep = "|---|" + "---|" * len(TEMAS)
    p("### Tokens de lectura")
    p("")
    p(cab)
    p(sep)
    for k in constr["claro"][1]:
        p("| %s | %s |" % (k, " | ".join("`%s`" % constr[n][1][k] for n in TEMAS)))
    p("")
    p("### Tokens Material 3 (ColorScheme)")
    p("")
    p(cab)
    p(sep)
    for k in constr["claro"][0]:
        p("| %s | %s |" % (k, " | ".join("`%s`" % constr[n][0][k] for n in TEMAS)))
    p("")
    p("### Sustituciones con *aumento de contraste*")
    p("")
    p(cab)
    p(sep)
    claves = []
    for n in TEMAS:
        hm, hl = construir(n, alto=True)
        for k, v in list(hm.items()) + list(hl.items()):
            base = constr[n][0].get(k, constr[n][1].get(k))
            if v != base and k not in claves:
                claves.append(k)
    for k in claves:
        celdas = []
        for n in TEMAS:
            hm, hl = construir(n, alto=True)
            v = hm.get(k, hl.get(k))
            base = constr[n][0].get(k, constr[n][1].get(k))
            celdas.append("`%s`" % v if v != base else "=")
        p("| %s | %s |" % (k, " | ".join(celdas)))
    p("")
    for nombre in TEMAS:
        m3, rl, filas, dist, fallos = evaluar(nombre)
        _, _, filas_hc, dist_hc, fallos_hc = evaluar(nombre, alto=True)
        todos_fallos += fallos + fallos_hc
        datos[nombre] = (m3, rl)
        t = TEMAS[nombre]
        p("### %s (`%s`)" % (t["nombre"], nombre))
        p("")
        p("Contraste, pares de lectura:")
        p("")
        p("| Primer plano | Fondo | Ratio | Min / objetivo | Nivel | Uso |")
        p("|---|---|---:|---|---|---|")
        for g, fg, fh, bg, bh, r, mi, ob, est, desc in filas:
            if g == "Lectura":
                p("| %s `%s` | %s `%s` | %.2f | %.1f / %.1f | %s | %s |" % (
                    fg, fh, bg, bh, r, mi, ob, est, desc))
        p("")
        p("Contraste, pares Material 3:")
        p("")
        p("| Primer plano | Fondo | Ratio | Min / objetivo | Nivel | Uso |")
        p("|---|---|---:|---|---|---|")
        for g, fg, fh, bg, bh, r, mi, ob, est, desc in filas:
            if g == "M3":
                p("| %s `%s` | %s `%s` | %.2f | %.1f / %.1f | %s | %s |" % (
                    fg, fh, bg, bh, r, mi, ob, est, desc))
        p("")
        p("Distincion de resaltados y estados (CIEDE2000; normal / protanopia / "
          "deuteranopia / tritanopia):")
        p("")
        p("| Par | Minimo | Normal | Prot. | Deut. | Trit. | Nota |")
        p("|---|---:|---:|---:|---:|---:|---|")
        for a, b, mi, vals, desc in dist:
            p("| %s / %s | %s | %s | %s |" % (
                a, b, ("%.0f" % mi) if mi else "-",
                " | ".join("%.1f" % v for v in vals), desc))
        p("")
        peor = min(x[5] for x in filas_hc)
        texto_hc = [x for x in filas_hc if x[1] == "readerText" and x[3] == "readerBackground"][0]
        p("Con *aumento de contraste* activado: texto de cuerpo %.2f:1, peor par verificado "
          "%.2f:1, fallos: %d." % (texto_hc[5], peor, len(fallos_hc)))
        p("")
    p("### Luz azul y brillo del fondo de lectura (indicador aproximado)")
    p("")
    p("No es una medida melanopica; con sRGB solo se puede comparar el canal azul lineal "
      "emitido por el fondo (que ocupa casi toda la pantalla) y su luminancia relativa Y. "
      "Indice 100 = tema claro.")
    p("")
    p("| Tema | Fondo | Y (brillo) | Azul lineal | Azul / (R+G+B) | Indice azul |")
    p("|---|---|---:|---:|---:|---:|")
    base_b = proxy_azul(datos["claro"][1]["readerBackground"])[0]
    for nombre, (m3, rl) in datos.items():
        bg = rl["readerBackground"]
        b, frac = proxy_azul(bg)
        p("| %s | `%s` | %.3f | %.3f | %.0f %% | %.0f |" % (
            TEMAS[nombre]["nombre"], bg, luminancia(bg), b, frac * 100,
            100 * b / base_b if base_b else 0))
    p("")
    if todos_fallos:
        p("**FALLOS:**")
        p("")
        for f in todos_fallos:
            p("- " + f)
    else:
        p("Resultado global: 0 fallos en %d temas (base y con aumento de contraste)." % len(TEMAS))
    return "\n".join(out), datos, todos_fallos


def generar_json(datos):
    temas = {}
    for nombre, (m3, rl) in datos.items():
        t = TEMAS[nombre]
        hc_m3, hc_rl = construir(nombre, alto=True)
        temas[nombre] = {
            "nombre": t["nombre"],
            "descripcion": t["descripcion"],
            "esOscuro": t["oscuro"],
            "varianteDe": t["variante"],
            "material3": m3,
            "lectura": rl,
            "altoContraste": {
                "material3": {k: v for k, v in hc_m3.items() if v != m3[k]},
                "lectura": {k: v for k, v in hc_rl.items() if v != rl[k]},
            },
        }
    return {
        "version": 1,
        "generadoPor": "tools/contraste.py",
        "formato": "#RRGGBB opaco; pageEdgeShadow es #AARRGGBB (con alfa). altoContraste "
                   "contiene solo los roles que se sustituyen al activar 'aumento de contraste'.",
        "notasUso": [
            "Los resaltados son opacos y se pintan detras del texto (EPUB/reflujo).",
            "En PDF (texto rasterizado) pintar los resaltados con BlendMode.Multiply sobre la "
            "pagina: sobre blanco da exactamente el color y el texto negro queda intacto.",
            "En temas oscuros los PDF se recolorean mapeando blanco->readerBackground y "
            "negro->readerText (interpolacion lineal por luminancia), no con inversion pura.",
            "ttsWordHighlight usa su propio color de texto ttsWordText.",
            "Senales no cromaticas obligatorias: frase = barra lateral ttsSentenceIndicator "
            "(3dp) ; palabra = subrayado ttsWordIndicator (2dp) ; busqueda = contorno "
            "searchOutline (1.5dp, 2dp la actual) ; seleccion = tiradores ; enlace = subrayado.",
        ],
        "temaPorDefecto": {"claro": "claro", "oscuro": "oscuro"},
        "temas": temas,
    }


def generar_css(datos):
    lineas = []
    for nombre, (m3, rl) in datos.items():
        hc_m3, hc_rl = construir(nombre, alto=True)
        for sufijo, a, b in (("", m3, rl), ("-hc", hc_m3, hc_rl)):
            lineas.append(".tema-%s%s {" % (nombre, sufijo))
            for k, v in list(a.items()) + list(b.items()):
                if len(v) == 9:  # #AARRGGBB -> rgba()
                    al = int(v[1:3], 16) / 255
                    r, g, bb = (int(v[i:i + 2], 16) for i in (3, 5, 7))
                    v = "rgba(%d,%d,%d,%.2f)" % (r, g, bb, al)
                lineas.append("  --%s: %s;" % (k, v))
            lineas.append("}")
    return "\n".join(lineas)


def sustituir(ruta, ini, fin, contenido):
    if not os.path.exists(ruta):
        print("AVISO: no existe %s; no se inserta el bloque." % ruta)
        return
    with open(ruta, encoding="utf-8") as f:
        texto = f.read()
    patron = re.compile(re.escape(ini) + r".*?" + re.escape(fin), re.S)
    if not patron.search(texto):
        print("AVISO: marcadores no encontrados en %s" % ruta)
        return
    texto = patron.sub(lambda _m: ini + "\n" + contenido + "\n" + fin, texto)
    with open(ruta, "w", encoding="utf-8") as f:
        f.write(texto)


def main():
    autotest()
    texto, datos, fallos = informe()
    print(texto)
    if "--solo-informe" not in sys.argv:
        os.makedirs(DIR_DISENO, exist_ok=True)
        with open(os.path.join(DIR_DISENO, "temas.json"), "w", encoding="utf-8") as f:
            json.dump(generar_json(datos), f, ensure_ascii=False, indent=2)
            f.write("\n")
        sustituir(os.path.join(DIR_DISENO, "TEMAS.md"),
                  "<!-- SALIDA-SCRIPT-INICIO -->", "<!-- SALIDA-SCRIPT-FIN -->", texto)
        sustituir(os.path.join(DIR_DISENO, "muestra-temas.html"),
                  "/* TOKENS-INICIO */", "/* TOKENS-FIN */", generar_css(datos))
    sys.exit(1 if fallos else 0)


if __name__ == "__main__":
    main()
