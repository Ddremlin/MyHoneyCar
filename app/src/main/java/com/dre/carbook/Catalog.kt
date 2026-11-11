package com.dre.carbook

/**
 * Встроенный справочник для подсказок: марка → модели → поколения.
 * Формат текста: строка марки «Марка|синонимы для поиска», строки моделей с отступом
 * «Модель: поколение; поколение». Поля в форме свободные — справочник только подсказывает.
 */
object Catalog {
    class Brand(val name: String, val aliases: List<String>, val models: List<Model>)
    class Model(val name: String, val generations: List<String>)

    val brands: List<Brand> by lazy { parse(DATA) }

    fun brand(name: String): Brand? = brands.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
        ?: brands.firstOrNull { b -> b.aliases.any { it.equals(name.trim(), ignoreCase = true) } }

    fun model(brand: String, model: String): Model? =
        brand(brand)?.models?.firstOrNull { it.name.equals(model.trim(), ignoreCase = true) }

    /** Подсказки марок: сначала совпадения с начала слова, затем по вхождению; ищем и по синонимам («тойота»). */
    fun suggestBrands(query: String, limit: Int = 10): List<String> =
        rank(query, brands) { listOf(it.name) + it.aliases }.map { it.name }.take(limit)

    fun suggestModels(brand: String, query: String, limit: Int = 40): List<String> =
        rank(query, brand(brand)?.models.orEmpty()) { listOf(it.name) }.map { it.name }.take(limit)

    fun suggestGenerations(brand: String, model: String, query: String, limit: Int = 12): List<String> =
        rank(query, model(brand, model)?.generations.orEmpty()) { listOf(it) }.take(limit)

    /** Разбор старого поля «Марка и модель» на марку и модель. */
    fun splitName(name: String): Pair<String, String> {
        val s = name.trim()
        val lower = s.lowercase()
        val hit = brands.flatMap { b -> (listOf(b.name) + b.aliases).map { b to it } }
            .filter { (_, n) -> lower == n.lowercase() || lower.startsWith(n.lowercase() + " ") }
            .maxByOrNull { it.second.length }
        if (hit != null) return hit.first.name to s.substring(hit.second.length).trim()
        val sp = s.indexOf(' ')
        return if (sp < 0) s to "" else s.substring(0, sp) to s.substring(sp + 1).trim()
    }

    private fun <T> rank(query: String, items: List<T>, keys: (T) -> List<String>): List<T> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return items
        fun score(t: T): Int {
            val ks = keys(t).map { it.lowercase() }
            return when {
                ks.any { it.startsWith(q) } -> 0
                ks.any { k -> k.split(' ', '-', '(').any { it.startsWith(q) } } -> 1
                ks.any { it.contains(q) } -> 2
                else -> 3
            }
        }
        return items.map { it to score(it) }.filter { it.second < 3 }.sortedBy { it.second }.map { it.first }
    }

    internal fun parse(text: String): List<Brand> {
        val out = mutableListOf<Brand>()
        var name = ""
        var aliases = emptyList<String>()
        var models = mutableListOf<Model>()
        fun flush() { if (name.isNotEmpty()) out += Brand(name, aliases, models) }
        text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            if (!line.startsWith(" ")) {
                flush()
                val parts = line.split('|')
                name = parts[0].trim()
                aliases = parts.getOrNull(1)?.trim()?.split(' ')?.filter { it.isNotBlank() }.orEmpty()
                models = mutableListOf()
            } else {
                // несколько моделей без поколений можно перечислить через «,»
                val t = line.trim()
                val colon = t.indexOf(':')
                if (colon < 0) t.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { models += Model(it, emptyList()) }
                else models += Model(t.substring(0, colon).trim(),
                    t.substring(colon + 1).split(';').map { it.trim() }.filter { it.isNotEmpty() })
            }
        }
        flush()
        return out
    }

    private val DATA = """
Lada|Лада ВАЗ
  Vesta: I (2015–2022); NG (2022–)
  Granta: I (2011–2018); I рестайлинг (2018–)
  Kalina: I 1118 (2004–2013); II 2192/2194 (2013–2018)
  Priora: 2170 (2007–2018)
  Largus: I (2012–2021); I рестайлинг (2021–)
  XRAY: I (2015–2022)
  Niva Legend: 2121/2131 (1977–)
  Niva Travel: 2123 (2002–)
  2107, 2109, 2110, 2112, 2114, 2115, Samara, Iskra
Toyota|Тойота
  Camry: XV30 (2001–2006); XV40 (2006–2011); XV50 (2011–2017); XV70 (2017–2024); XV80 (2024–)
  Corolla: E120 (2000–2007); E150 (2006–2013); E160/E170 (2012–2019); E210 (2018–)
  RAV4: XA20 (2000–2005); XA30 (2005–2012); XA40 (2012–2019); XA50 (2018–)
  Land Cruiser: 100 (1998–2007); 200 (2007–2021); 300 (2021–)
  Land Cruiser Prado: 120 (2002–2009); 150 (2009–2023); 250 (2023–)
  Highlander: XU20 (2000–2007); XU40 (2007–2013); XU50 (2013–2019); XU70 (2019–)
  Avensis: T22 (1997–2003); T25 (2003–2009); T27 (2009–2018)
  Auris: E150 (2006–2012); E180 (2012–2018)
  Yaris, Hilux, Fortuner, C-HR, Prius, Venza, Alphard, Mark II, Crown
Lexus|Лексус
  RX: XU10 (1998–2003); XU30 (2003–2008); AL10 (2008–2015); AL20 (2015–2022); ALH10 (2022–)
  NX: AZ10 (2014–2021); AZ20 (2021–)
  LX: J100 (1998–2007); J200 (2007–2021); J300 (2021–)
  ES, IS, GS, GX, LS, UX
Kia|Киа Кия
  Rio: II (2005–2011); III (2011–2017); IV (2017–)
  Ceed: ED (2006–2012); JD (2012–2018); CD (2018–)
  Sportage: II KM (2004–2010); III SL (2010–2016); IV QL (2015–2021); V NQ5 (2021–)
  Sorento: I BL (2002–2009); II XM (2009–2014); III UM (2014–2020); IV MQ4 (2020–)
  Cerato: I LD (2004–2009); II TD (2008–2013); III YD (2013–2018); IV BD (2018–)
  Optima: III TF (2010–2015); IV JF (2015–2020)
  K5: DL3 (2020–)
  Soul: I AM (2008–2013); II PS (2013–2019); III SK3 (2019–)
  Picanto, Seltos, Mohave, Carnival, Stinger, K8, K9
Hyundai|Хендай Хёндай Хундай Хюндай
  Solaris: I RB (2010–2017); II HC (2017–2022)
  Creta: I GS (2016–2021); II SU2 (2021–)
  Elantra: IV HD (2006–2011); V MD (2010–2016); VI AD (2015–2020); VII CN7 (2020–)
  Tucson: I JM (2004–2010); III TL (2015–2020); IV NX4 (2020–)
  ix35: LM (2009–2015)
  Santa Fe: I SM (2000–2006); II CM (2006–2012); III DM (2012–2018); IV TM (2018–2023); V MX5 (2023–)
  Sonata: V NF (2004–2010); VI YF (2009–2014); VII LF (2014–2019); VIII DN8 (2019–)
  i30: I FD (2007–2012); II GD (2011–2017); III PD (2016–)
  Getz, Accent, Matrix, Palisade, Staria, H-1, Grand Starex
Renault|Рено
  Logan: I (2004–2015); II (2014–2022)
  Sandero: I (2009–2014); II (2014–2022)
  Duster: I (2010–2021); II (2020–)
  Megane: II (2002–2009); III (2008–2016); IV (2016–)
  Kaptur, Arkana, Fluence, Koleos, Clio, Scenic, Symbol, Kangoo
Volkswagen|Фольксваген VW
  Polo: IV 9N (2001–2009); V седан (2010–2020); VI лифтбек (2020–)
  Golf: IV (1997–2006); V (2003–2009); VI (2008–2013); VII (2012–2020); VIII (2019–)
  Passat: B5 (1996–2005); B6 (2005–2010); B7 (2010–2015); B8 (2014–)
  Jetta: V (2005–2010); VI (2010–2018); VII (2018–)
  Tiguan: I (2007–2016); II (2016–2024); III (2024–)
  Touareg: I 7L (2002–2010); II 7P (2010–2018); III CR (2018–)
  Transporter: T4 (1990–2003); T5 (2003–2015); T6 (2015–)
  Passat CC, Teramont, Touran, Caddy, Multivan, Amarok
Skoda|Шкода
  Octavia: A4 Tour (1996–2010); A5 (2004–2013); A7 (2012–2020); A8 (2019–)
  Rapid: I (2012–2020); II (2020–)
  Superb: B5 (2001–2008); B6 (2008–2015); B8 (2015–2023); B9 (2023–)
  Fabia, Kodiaq, Karoq, Yeti, Kamiq
Audi|Ауди
  A4: B6 (2000–2004); B7 (2004–2008); B8 (2007–2015); B9 (2015–)
  A6: C5 (1997–2004); C6 (2004–2011); C7 (2011–2018); C8 (2018–)
  Q5: 8R (2008–2017); FY (2016–)
  Q7: 4L (2005–2015); 4M (2015–)
  A3, A5, A8, Q3, Q8, TT, e-tron
BMW|БМВ
  3 Series: E46 (1998–2006); E90 (2005–2013); F30 (2011–2019); G20 (2018–)
  5 Series: E39 (1995–2004); E60 (2003–2010); F10 (2009–2017); G30 (2016–2023); G60 (2023–)
  7 Series: E65 (2001–2008); F01 (2008–2015); G11 (2015–2022); G70 (2022–)
  X3: E83 (2003–2010); F25 (2010–2017); G01 (2017–2024); G45 (2024–)
  X5: E53 (1999–2006); E70 (2006–2013); F15 (2013–2018); G05 (2018–)
  X6: E71 (2008–2014); F16 (2014–2019); G06 (2019–)
  1 Series, X1, X4, X7, 4 Series
Mercedes-Benz|Мерседес Mercedes
  C-Class: W203 (2000–2007); W204 (2007–2014); W205 (2014–2021); W206 (2021–)
  E-Class: W210 (1995–2003); W211 (2002–2009); W212 (2009–2016); W213 (2016–2023); W214 (2023–)
  S-Class: W220 (1998–2005); W221 (2005–2013); W222 (2013–2020); W223 (2020–)
  M-Class / GLE: W163 (1997–2005); W164 (2005–2011); W166 (2011–2019); V167 (2019–)
  GLC: X253 (2015–2022); X254 (2022–)
  GL / GLS: X164 (2006–2012); X166 (2012–2019); X167 (2019–)
  A-Class, B-Class, CLA, G-Class, Vito, Sprinter
Nissan|Ниссан
  Qashqai: J10 (2006–2013); J11 (2013–2021); J12 (2021–)
  X-Trail: T30 (2000–2007); T31 (2007–2014); T32 (2013–2022); T33 (2021–)
  Almera: N16 (2000–2006); G15 (2012–2018)
  Teana: J31 (2003–2008); J32 (2008–2014); L33 (2014–2020)
  Murano: Z50 (2002–2007); Z51 (2007–2014); Z52 (2014–)
  Note, Juke, Patrol, Pathfinder, Terrano, Tiida, Leaf, Primera, Sentra
Mitsubishi|Мицубиси Митсубиси
  Lancer: IX (2000–2010); X (2007–2017)
  Outlander: I CU (2001–2008); II CW (2005–2012); III GF (2012–2021); IV GN (2021–)
  Pajero: III (1999–2006); IV (2006–2021)
  Pajero Sport: II (2008–2016); III (2015–)
  ASX, L200, Eclipse Cross, Colt
Mazda|Мазда
  3: BK (2003–2009); BL (2009–2013); BM (2013–2019); BP (2019–)
  6: GG (2002–2008); GH (2007–2012); GJ (2012–)
  CX-5: KE (2011–2017); KF (2017–)
  2, CX-7, CX-9, CX-30, CX-60
Honda|Хонда
  CR-V: II RD (2001–2006); III RE (2006–2012); IV RM (2012–2018); V RW (2016–2022); VI (2022–)
  Accord: VII (2002–2008); VIII (2008–2013); IX (2012–2017)
  Civic, Fit, Jazz, Pilot, HR-V, Stepwgn
Subaru|Субару
  Forester: SG (2002–2008); SH (2007–2013); SJ (2012–2018); SK (2018–)
  Outback: BP (2003–2009); BR (2009–2014); BS (2014–2020); BT (2020–)
  Impreza, XV, Legacy, Tribeca
Ford|Форд
  Focus: I (1998–2005); II (2004–2011); III (2010–2019); IV (2018–)
  Mondeo: III (2000–2007); IV (2007–2014); V (2014–2022)
  Kuga: I (2008–2012); II (2012–2019); III (2019–)
  Fiesta, EcoSport, Explorer, Transit, Galaxy, S-Max, Fusion
Chevrolet|Шевроле
  Niva, Cruze, Lacetti, Aveo, Cobalt, Captiva, Spark, Tahoe, Lanos, Orlando
Opel|Опель
  Astra: G (1998–2009); H (2004–2014); J (2009–2015); K (2015–2021); L (2021–)
  Corsa, Insignia, Zafira, Mokka, Vectra, Antara, Meriva
Peugeot|Пежо
  206, 207, 208, 307, 308, 408, 2008, 3008, 4008, 5008, Partner, Boxer
Citroen|Ситроен
  C3, C4, C5, C4 Picasso, C5 Aircross, C-Elysee, Berlingo, Jumper
Volvo|Вольво
  XC90: I (2002–2014); II (2014–)
  XC60: I (2008–2017); II (2017–)
  XC40, XC70, S60, S80, S90, V40
Land Rover|Ленд Ровер
  Range Rover, Range Rover Sport, Range Rover Evoque, Range Rover Velar, Discovery, Discovery Sport, Defender, Freelander
Porsche|Порше
  Cayenne: 955/957 (2002–2010); 958 (2010–2017); 9YA (2017–)
  Macan, Panamera, 911, Taycan
Infiniti|Инфинити
  FX, QX50, QX56, QX60, QX70, QX80, G, Q50
Suzuki|Сузуки
  Grand Vitara, Vitara, SX4, Swift, Jimny, Liana
Daewoo|Дэу Дэо
  Nexia, Matiz, Gentra, Lanos
UAZ|УАЗ
  Patriot, Hunter, Буханка, Pickup, Профи
GAZ|ГАЗ
  Газель Next, Газель Бизнес, Соболь, Волга 3110, Волга 31105
Geely|Джили
  Coolray, Atlas, Atlas Pro, Monjaro, Tugella, Emgrand, Okavango, Preface
Chery|Чери
  Tiggo 4, Tiggo 4 Pro, Tiggo 7, Tiggo 7 Pro, Tiggo 7 Pro Max, Tiggo 8, Tiggo 8 Pro, Tiggo 8 Pro Max, Tiggo 9, Arrizo 8
Haval|Хавал Хавейл
  Jolion, F7, F7x, H6, H9, Dargo, M6
Changan|Чанган
  CS35 Plus, CS55 Plus, CS75 Plus, UNI-K, UNI-V, UNI-S, Alsvin
Exeed|Эксид
  TXL, LX, VX, RX
Omoda|Омода
  C5, S5
Jetour|Джетур
  X70, X90, Dashing
Tank|Танк
  300, 500
Tenet|Тенет
  T4, T7, T8
Great Wall|Грейт Волл
  Hover H3, Hover H5, Wingle
Datsun|Датсун
  on-DO, mi-DO
Lifan|Лифан
  Solano, X50, X60, Smily
Moskvich|Москвич
  3, 3e
Jeep|Джип
  Grand Cherokee, Cherokee, Wrangler, Compass, Renegade
SsangYong|Ссанг Йонг
  Kyron, Actyon, Rexton
Fiat|Фиат
  500, Albea, Doblo, Ducato
Mini|Мини
  Cooper, Countryman
Tesla|Тесла
  Model 3, Model S, Model X, Model Y
Zeekr|Зикр
  001, 7X, 9X
Li Auto|Лисян LiXiang
  L6, L7, L8, L9
""".trimIndent().replace("\n  ", "\n ")
}
