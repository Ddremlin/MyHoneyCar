package com.dre.carbook

import android.content.Context
import androidx.core.content.edit
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Анализ данных ELM327 нейросетью Claude: коды ошибок, сводка по датчикам,
 * регламент ТО и последние ремонты уходят в Claude API, ответ — разбор для владельца.
 *
 * Ключ API вводит сам пользователь, он хранится только в приватных настройках приложения
 * (не попадает в резервную копию). VIN и госномер в запрос не включаются.
 */
object Ai {
    const val MODEL = "claude-opus-5-5"
    private const val PREFS = "ai"
    private const val KEY = "apiKey"

    fun apiKey(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.takeIf { it.isNotBlank() }

    fun setApiKey(ctx: Context, key: String?) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
        if (key.isNullOrBlank()) remove(KEY) else putString(KEY, key.trim())
    }

    /** Идёт ли сейчас анализ (id автомобиля) и последняя ошибка — живут дольше экрана. */
    val running = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<String?>(null)
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

    /** Запускает анализ в фоне приложения; результат сохраняется в автомобиль. */
    fun start(ctx: Context, carId: String) {
        if (running.value != null) return
        val repo = ctx.repo
        val key = apiKey(ctx) ?: run { error.value = "Укажите ключ API"; return }
        val d = repo.data.value
        val car = d.cars.firstOrNull { it.id == carId } ?: return
        val prompt = buildPrompt(d, car)
        running.value = carId
        error.value = null
        scope.launch {
            try {
                when (val r = analyze(key, prompt)) {
                    is Result.Ok -> repo.saveAiReport(carId, r.text, System.currentTimeMillis())
                    is Result.Error -> error.value = r.message
                }
            } catch (e: Exception) {
                error.value = "Ошибка: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                running.value = null
            }
        }
    }

    /** Есть ли что анализировать: ошибки или датчики из ELM OBD. */
    fun hasObdData(car: Car) = car.sensors.isNotEmpty() || car.dtcAt != null

    sealed interface Result {
        data class Ok(val text: String) : Result
        data class Error(val message: String) : Result
    }

    suspend fun analyze(apiKey: String, prompt: String): Result = withContext(Dispatchers.IO) {
        val client = AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .timeout(Duration.ofMinutes(5))
            .build()
        try {
            val params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(16000L)
                .system(SYSTEM)
                .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.MEDIUM).build())
                // при отказе модели (ложное срабатывание фильтров) сервер сам повторит запрос на запасной модели
                .addBeta("server-side-fallback-2026-07-01")
                .fallbacksDefault()
                .addUserMessage(prompt)
                .build()
            val msg = client.beta().messages().create(params)
            val text = msg.content().mapNotNull { b -> b.text().orElse(null)?.text() }.joinToString("\n").trim()
            when (msg.stopReason().orElse(null)) {
                BetaStopReason.REFUSAL -> Result.Error("Нейросеть отказалась отвечать на этот запрос.")
                BetaStopReason.MAX_TOKENS ->
                    if (text.isNotEmpty()) Result.Ok("$text\n\n(ответ обрезан по длине)") else Result.Error("Ответ слишком длинный, попробуйте ещё раз.")
                else -> if (text.isNotEmpty()) Result.Ok(text) else Result.Error("Пустой ответ.")
            }
        } catch (e: UnauthorizedException) {
            Result.Error("Неверный ключ API — проверьте его в настройках анализа.")
        } catch (e: PermissionDeniedException) {
            Result.Error("Ключу API запрещён доступ к модели $MODEL.")
        } catch (e: RateLimitException) {
            Result.Error("Слишком много запросов, попробуйте через минуту.")
        } catch (e: BadRequestException) {
            Result.Error("Запрос отклонён: ${e.message}")
        } catch (e: InternalServerException) {
            Result.Error("Сервис временно недоступен, попробуйте позже.")
        } catch (e: AnthropicServiceException) {
            Result.Error("Ошибка API (${e.statusCode()}): ${e.message}")
        } catch (e: AnthropicIoException) {
            Result.Error("Нет связи с сервером: проверьте интернет.")
        } finally {
            client.close()
        }
    }

    private val SYSTEM = """
        Ты — опытный автодиагност. Владелец автомобиля прислал данные из своего приложения учёта:
        марку и модель, пробег, коды ошибок OBD-II и сводку по датчикам, снятую адаптером ELM327
        (минимум / среднее / максимум / последнее значение за последние минуты опроса), а также регламент ТО
        и последние ремонты.

        Разбери данные для владельца — человека без специального образования:
        1. Итог — 1–3 предложения: всё в порядке / есть на что обратить внимание / нужен срочный ремонт.
        2. Коды ошибок — расшифровка каждого кода, вероятные причины именно для этой модели
           (от самой вероятной к менее вероятной), что проверить и можно ли пока ездить.
           Ожидающие (pending) и сохранённые коды в данных не разделены — учитывай это.
        3. Датчики — что в норме, что вызывает вопросы. Учитывай условия съёмки: прогрет ли двигатель
           (температура ОЖ), холостой ход или движение (обороты, скорость), сколько замеров.
           Особое внимание: коррекции топлива, напряжение бортсети, температура ОЖ, датчики кислорода,
           расход воздуха относительно оборотов и объёма двигателя.
        4. ТО — что по регламенту просрочено или скоро подойдёт и связано ли это с найденным.
        5. Что сделать в первую очередь — короткий список по важности.

        Если данных мало или условия неподходящие (холодный двигатель, только холостой ход), прямо скажи об этом
        и посоветуй, что и как снять для точного вывода. Не придумывай значения, которых нет в данных.
        Если параметр зависит от конкретного двигателя и ты не уверен в норме — так и скажи.
        В конце одной строкой напомни, что это предварительная оценка, не заменяющая диагностику на СТО.

        Пиши по-русски, простым текстом для экрана телефона: без таблиц и без разметки Markdown
        (никаких #, ** и |). Заголовки разделов — отдельной строкой, пункты списков начинай с «• ».
    """.trimIndent()

    /** Текст запроса: всё, что знаем об автомобиле. Без VIN и госномера. */
    fun buildPrompt(d: AppData, car: Car, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): String =
        buildString {
            fun time(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).let {
                "%s %02d:%02d".format(Locale.ROOT, it.toLocalDate(), it.hour, it.minute)
            }
            fun n(x: Double) = when {
                x == Math.rint(x) && kotlin.math.abs(x) < 1e6 -> x.toLong().toString()
                kotlin.math.abs(x) >= 100 -> "%.0f".format(Locale.ROOT, x)
                kotlin.math.abs(x) >= 10 -> "%.1f".format(Locale.ROOT, x)
                else -> "%.3f".format(Locale.ROOT, x).trimEnd('0').trimEnd('.')
            }

            appendLine("Сегодня: $today")
            appendLine()
            appendLine("АВТОМОБИЛЬ")
            appendLine("Марка и модель: ${car.name}")
            if (car.generation.isNotBlank()) appendLine("Поколение / кузов: ${car.generation}")
            car.year?.let { appendLine("Год выпуска: $it") }
            appendLine("Пробег: ${d.currentKm(car)} км")
            car.tankLiters?.let { appendLine("Объём бака: $it л") }
            fuelStats(d.expensesOf(car.id)).avgPer100?.let { appendLine("Средний расход по заправкам: ${"%.1f".format(Locale.ROOT, it)} л/100 км") }

            appendLine()
            appendLine("КОДЫ ОШИБОК")
            if (car.dtcAt == null) appendLine("Не читались.")
            else {
                appendLine("Прочитаны: ${time(car.dtcAt)}")
                car.mil?.let { appendLine("Check Engine: ${if (it) "горит" else "не горит"}") }
                appendLine(if (car.dtc.isEmpty()) "Ошибок нет." else car.dtc.joinToString(", "))
            }

            appendLine()
            appendLine("ДАТЧИКИ (ELM327)")
            if (car.sensors.isEmpty()) appendLine("Нет данных.")
            else {
                val from = car.sensors.minOf { it.from }
                val to = car.sensors.maxOf { it.to }
                appendLine("Окно опроса: ${time(from)} — ${time(to)} (${((to - from) / 1000).coerceAtLeast(0)} с)")
                appendLine("Формат: параметр [PID]: мин / среднее / макс / последнее, число замеров")
                car.sensors.forEach { s ->
                    val pid = if (s.pid >= 0) " [%02X]".format(s.pid) else ""
                    val unit = if (s.unit.isNotBlank()) " ${s.unit}" else ""
                    appendLine("${s.name}$pid: ${n(s.min)} / ${n(s.avg)} / ${n(s.max)} / ${n(s.last)}$unit, n=${s.n}")
                }
            }

            val km = d.currentKm(car)
            val services = sortedStatuses(d.servicesOf(car.id), km, today)
            if (services.isNotEmpty()) {
                appendLine()
                appendLine("РЕГЛАМЕНТ ТО")
                services.filter { !it.item.isInsurance() }.forEach { st ->
                    val s = st.item
                    val interval = listOfNotNull(s.intervalKm?.let { "$it км" }, s.intervalMonths?.let { "$it мес." })
                        .joinToString(" / ").ifEmpty { "без интервала" }
                    val last = listOfNotNull(s.lastKm?.let { "на $it км" }, s.lastDate?.toString())
                        .joinToString(", ").ifEmpty { "не отмечено" }
                    val state = when (st.state) {
                        DueState.OVERDUE -> "ПРОСРОЧЕНО"
                        DueState.SOON -> "скоро"
                        DueState.OK -> "в норме"
                        DueState.UNKNOWN -> "нет данных"
                    }
                    val left = listOfNotNull(st.leftKm?.let { "осталось $it км" }, st.leftDays?.let { "осталось $it дн." })
                        .joinToString(", ")
                    appendLine("• ${s.name}: интервал $interval; последний раз $last; $state" + if (left.isNotEmpty()) " ($left)" else "")
                }
            }

            val repairs = d.expensesOf(car.id)
                .filter { it.category in REPAIR_CATEGORIES && it.note.isNotBlank() }
                .sortedByDescending { it.date }
                .take(15)
            if (repairs.isNotEmpty()) {
                appendLine()
                appendLine("ПОСЛЕДНИЕ РАБОТЫ И ЗАПЧАСТИ")
                repairs.forEach { e ->
                    val at = e.mileage?.let { ", $it км" }.orEmpty()
                    appendLine("• ${e.date}$at — ${e.category.title}: ${e.note.take(200)}")
                }
            }
        }.trimEnd()

    private val REPAIR_CATEGORIES = setOf(Category.SERVICE, Category.REPAIR, Category.PARTS, Category.TIRES)
}
