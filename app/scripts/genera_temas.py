#!/usr/bin/env python3
"""Genera app/src/main/kotlin/com/lectorvoz/app/ui/theme/TemasGenerados.kt desde
docs/diseno/temas.json (fuente de verdad de los colores). Uso, desde la raiz:
    python3 app/scripts/genera_temas.py
"""
import json, os

RAIZ = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ENTRADA = os.path.join(RAIZ, "docs", "diseno", "temas.json")
SALIDA = os.path.join(RAIZ, "app", "src", "main", "kotlin", "com", "lectorvoz", "app", "ui", "theme", "TemasGenerados.kt")


def argb(valor: str) -> str:
    h = valor.lstrip("#").upper()
    if len(h) == 6:
        h = "FF" + h
    return "0x" + h + "L"


def mapa(d: dict, sangria: str) -> str:
    if not d:
        return "emptyMap()"
    lineas = ",\n".join(f'{sangria}    "{k}" to {argb(v)}' for k, v in d.items())
    return "mapOf(\n" + lineas + f",\n{sangria})"


def main() -> None:
    datos = json.load(open(ENTRADA, encoding="utf-8"))
    partes = []
    for clave, t in datos["temas"].items():
        alto = t.get("altoContraste", {})
        s = "        "
        partes.append(
            "    TemaTokens(\n"
            f'        clave = "{clave}",\n'
            f'        esOscuro = {"true" if t["esOscuro"] else "false"},\n'
            f"        material3 = {mapa(t['material3'], s)},\n"
            f"        lectura = {mapa(t['lectura'], s)},\n"
            f"        altoMaterial3 = {mapa(alto.get('material3', {}), s)},\n"
            f"        altoLectura = {mapa(alto.get('lectura', {}), s)},\n"
            "    )"
        )
    codigo = (
        "// GENERADO por app/scripts/genera_temas.py desde docs/diseno/temas.json. NO EDITAR A MANO.\n"
        "package com.lectorvoz.app.ui.theme\n\n"
        "/** Tokens de un tema tal como estan en temas.json (colores ARGB). */\n"
        "internal class TemaTokens(\n"
        "    val clave: String,\n"
        "    val esOscuro: Boolean,\n"
        "    val material3: Map<String, Long>,\n"
        "    val lectura: Map<String, Long>,\n"
        "    val altoMaterial3: Map<String, Long>,\n"
        "    val altoLectura: Map<String, Long>,\n"
        ")\n\n"
        "/** Los cinco temas de docs/diseno/temas.json, en su orden. */\n"
        "internal val TEMAS_GENERADOS: List<TemaTokens> = listOf(\n"
        + ",\n".join(partes)
        + ",\n)\n"
    )
    os.makedirs(os.path.dirname(SALIDA), exist_ok=True)
    open(SALIDA, "w", encoding="utf-8").write(codigo)
    print("Escrito", SALIDA)


if __name__ == "__main__":
    main()
