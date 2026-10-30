package com.lectorvoz.core.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SpanishNormalizerTest {
    private val norm = SpanishNormalizer()

    private fun say(text: String): String {
        val r = norm.normalize(text)
        assertMapValid(r, text.length)
        return r.text
    }

    @Test
    fun numerosConGeneroSegunSustantivo() {
        assertThat(say("Tengo 1 casa y 1 perro.")).isEqualTo("Tengo una casa y un perro.")
        assertThat(say("Leyó 21 páginas en 21 días.")).isEqualTo("Leyó veintiuna páginas en veintiún días.")
        assertThat(say("Había 200 personas.")).isEqualTo("Había doscientas personas.")
        assertThat(say("Uno de cada 1 de ellos")).isEqualTo("Uno de cada uno de ellos")
        assertThat(say("Llegaron 3.000.000 personas.")).isEqualTo("Llegaron tres millones de personas.")
    }

    @Test
    fun anosYGrandesNumeros() {
        assertThat(say("En 1492 llegó.")).isEqualTo("En mil cuatrocientos noventa y dos llegó.")
        assertThat(say("Costó 1.250.000 euros")).isEqualTo("Costó un millón doscientos cincuenta mil euros")
        assertThat(say("Hay 7 000 libros")).isEqualTo("Hay siete mil libros")
    }

    @Test
    fun decimales() {
        assertThat(say("Mide 1,5 metros.")).isEqualTo("Mide uno coma cinco metros.")
        assertThat(say("Pi es 3.14")).isEqualTo("Pi es tres punto catorce")
        assertThat(say("Vale 0,05")).isEqualTo("Vale cero coma cero cinco")
        assertThat(say("Es 2,71828")).isEqualTo("Es dos coma siete uno ocho dos ocho")
    }

    @Test
    fun ordinales() {
        assertThat(say("Quedó 1.º y ella 2.ª")).isEqualTo("Quedó primero y ella segunda")
        assertThat(say("El 3er hombre")).isEqualTo("El tercer hombre")
        assertThat(say("el 1.er día")).isEqualTo("el primer día")
        assertThat(say("los 2.os puestos")).isEqualTo("los segundos puestos")
    }

    @Test
    fun porcentajesYMonedas() {
        assertThat(say("Subió un 50 %.")).isEqualTo("Subió un cincuenta por ciento.")
        assertThat(say("Bajó un 1,5%")).isEqualTo("Bajó un uno coma cinco por ciento")
        assertThat(say("Cuesta 20 €")).isEqualTo("Cuesta veinte euros")
        assertThat(say("Cuesta 1 €")).isEqualTo("Cuesta un euro")
        assertThat(say("Cuesta 21€")).isEqualTo("Cuesta veintiún euros")
        assertThat(say("Cuesta 1,50 €")).isEqualTo("Cuesta un euro con cincuenta céntimos")
        assertThat(say("Pagó $5")).isEqualTo("Pagó cinco dólares")
        assertThat(say("Pagó 3 $")).isEqualTo("Pagó tres dólares")
        assertThat(say("Ganó 1.000.000 €")).isEqualTo("Ganó un millón de euros")
    }

    @Test
    fun horas() {
        assertThat(say("a las 2:30 de la madrugada")).isEqualTo("a las dos y media de la madrugada")
        assertThat(say("a las 1:15")).isEqualTo("a las una y cuarto")
        assertThat(say("a las 7:00")).isEqualTo("a las siete en punto")
        assertThat(say("a las 14:05 h")).isEqualTo("a las catorce horas y cinco minutos")
        assertThat(say("a las 21:00 h.")).isEqualTo("a las veintiuna horas.")
        assertThat(say("a las 8:10")).isEqualTo("a las ocho y diez")
    }

    @Test
    fun fechas() {
        assertThat(say("Nació el 12/10/1492.")).isEqualTo("Nació el doce de octubre de mil cuatrocientos noventa y dos.")
        assertThat(say("Firmado 1-2-1999")).isEqualTo("Firmado uno de febrero de mil novecientos noventa y nueve")
        assertThat(say("El 12 de octubre de 1492")).isEqualTo("El doce de octubre de mil cuatrocientos noventa y dos")
    }

    @Test
    fun romanosEnContexto() {
        assertThat(say("en el siglo XV.")).isEqualTo("en el siglo quince.")
        assertThat(say("Capítulo IV")).isEqualTo("Capítulo cuatro")
        assertThat(say("el tomo III")).isEqualTo("el tomo tres")
        assertThat(say("reinó Felipe II en")).isEqualTo("reinó Felipe segundo en")
        assertThat(say("con Isabel II y")).isEqualTo("con Isabel segunda y")
        assertThat(say("Alfonso XIII reinó")).isEqualTo("Alfonso trece reinó")
        assertThat(say("la II Guerra Mundial")).isEqualTo("la segunda Guerra Mundial")
        assertThat(say("cap. IX")).isEqualTo("capítulo nueve")
    }

    @Test
    fun romanosAmbiguosNoSeTocan() {
        assertThat(say("MI casa")).isEqualTo("MI casa")
        assertThat(say("Yo VI la luz")).isEqualTo("Yo VI la luz")
        assertThat(say("DI que sí")).isEqualTo("DI que sí")
        assertThat(say("YO VI LA LUZ")).isEqualTo("YO VI LA LUZ")
        assertThat(say("el XV")).isEqualTo("el XV")
    }

    @Test
    fun abreviaturas() {
        assertThat(say("El Sr. Pérez y la Dra. Gil")).isEqualTo("El señor Pérez y la doctora Gil")
        assertThat(say("Peras, uvas, etc.")).isEqualTo("Peras, uvas, etcétera")
        assertThat(say("Ver pág. 5")).isEqualTo("Ver página cinco")
        assertThat(say("D. Quijote")).isEqualTo("don Quijote")
        assertThat(say("Viajó a EE. UU. en 1990")).isEqualTo("Viajó a Estados Unidos en mil novecientos noventa")
        assertThat(say("en el 200 a. C.")).isEqualTo("en el doscientos antes de Cristo")
        assertThat(say("el nº 7")).isEqualTo("el número siete")
    }

    @Test
    fun unidades() {
        assertThat(say("Corrió 5 km")).isEqualTo("Corrió cinco kilómetros")
        assertThat(say("Pesa 1 kg")).isEqualTo("Pesa un kilo")
        assertThat(say("Un piso de 90 m2")).isEqualTo("Un piso de noventa metros cuadrados")
        assertThat(say("Durmió 21 h")).isEqualTo("Durmió veintiuna horas")
        assertThat(say("Iba a 120 km/h")).isEqualTo("Iba a ciento veinte kilómetros por hora")
        assertThat(say("Hace 25 ºC")).isEqualTo("Hace veinticinco grados centígrados")
    }

    @Test
    fun simbolos() {
        assertThat(say("Juan & María")).isEqualTo("Juan y María")
        assertThat(say("2 + 2 = 4")).isEqualTo("dos más dos igual a cuatro")
        assertThat(say("escribe a ana@correo.es")).isEqualTo("escribe a ana arroba correo punto es")
        assertThat(say("visita https://www.rae.es/diccionario.")).isEqualTo("visita enlace a rae punto es.")
        assertThat(say("ver http://dle.rae.es/?w=casa&o=h#x larga")).isEqualTo("ver enlace a dle punto rae punto es larga")
        assertThat(say("Hacía -5 grados")).isEqualTo("Hacía menos cinco grados")
        assertThat(say("Guerra de 1914-1918")).isEqualTo("Guerra de mil novecientos catorce a mil novecientos dieciocho")
        assertThat(say("Añade 1/2 taza")).isEqualTo("Añade un medio taza")
    }

    @Test
    fun siglas() {
        assertThat(say("La ONU y la OTAN")).isEqualTo("La ONU y la OTAN")
        assertThat(say("Lo emitió RTVE")).isEqualTo("Lo emitió erre te uve e")
        assertThat(say("Su DNI caducó")).isEqualTo("Su de ene i caducó")
        assertThat(say("Fue al BCX hoy")).isEqualTo("Fue al be ce equis hoy")
        assertThat(say("La UNESCO y la FAO")).isEqualTo("La UNESCO y la FAO")
    }

    @Test
    fun rayaYComillasNoSePronuncian() {
        assertThat(say("—¿Qué hora es? —preguntó Martín.")).isEqualTo("¿Qué hora es? preguntó Martín.")
        assertThat(say("«Hola», dijo. “Sí”")).isEqualTo("Hola, dijo. Sí")
        assertThat(say("—Ya—dijo él—. Vamos.")).isEqualTo("Ya dijo él. Vamos.")
        assertThat(say("franco-alemán")).isEqualTo("franco alemán")
        assertThat(say("l'eau y O'Donnell")).isEqualTo("l'eau y O'Donnell")
    }

    @Test
    fun espaciosColapsadosYObjetosIgnorados() {
        assertThat(say("  Hola ￼   mundo  ")).isEqualTo("Hola mundo")
    }

    @Test
    fun genero() {
        assertThat(SpanishNormalizer.nounGender("casa")).isEqualTo(Gender.FEMININE)
        assertThat(SpanishNormalizer.nounGender("canción")).isEqualTo(Gender.FEMININE)
        assertThat(SpanishNormalizer.nounGender("ciudades")).isEqualTo(Gender.FEMININE)
        assertThat(SpanishNormalizer.nounGender("día")).isEqualTo(Gender.MASCULINE)
        assertThat(SpanishNormalizer.nounGender("problemas")).isEqualTo(Gender.MASCULINE)
        assertThat(SpanishNormalizer.nounGender("libro")).isEqualTo(Gender.MASCULINE)
        assertThat(SpanishNormalizer.nounGender("veces")).isEqualTo(Gender.FEMININE)
    }

    @Test
    fun mapaApuntaAlNumeroOriginal() {
        val text = "El 12 de mayo"
        val r = norm.normalize(text)
        val doce = r.text.indexOf("doce")
        assertThat(r.toSource[doce]).isEqualTo(3)
        assertThat(r.toSource[r.text.indexOf("mayo")]).isEqualTo(text.indexOf("mayo"))
    }

    companion object {
        /** Propiedades del mapa: misma longitud, monotono y dentro del texto mostrado. */
        fun assertMapValid(r: MappedText, displayLength: Int) {
            assertThat(r.toSource.size).isEqualTo(r.text.length)
            for (i in r.toSource.indices) {
                assertThat(r.toSource[i]).isAtLeast(0)
                assertThat(r.toSource[i]).isLessThan(displayLength)
                if (i > 0) assertThat(r.toSource[i]).isAtLeast(r.toSource[i - 1])
            }
        }
    }

    @Test
    fun digitosUnicodeNoAsciiNoRompen() {
        assertThat(say("Tengo ３ gatos")).isEqualTo("Tengo tres gatos")
        assertThat(say("Capítulo ٣")).isEqualTo("Capítulo tres")
        assertThat(say("Cuesta €３")).isEqualTo("Cuesta tres euros")
        assertThat(say("Año ２０２４.")).isEqualTo("Año dos mil veinticuatro.")
        assertThat(say("Valor \uD835\uDFD1 fin")).isEqualTo("Valor tres fin")
        assertThat(SpanishNumbers.digits("０٥")).isEqualTo("cero cinco")
    }
}
