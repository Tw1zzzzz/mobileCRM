package com.pulse.core.model

import com.pulse.core.util.Tint
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Generated from Pulse/Models/PlayerSurvey.swift and MindsetTest.swift (texts must match iOS).

/** What the player wants from Pulse (first-login questionnaire, step «Цели»). */
@Serializable
enum class FocusGoal(val title: String, val icon: String, val tint: Tint) {
    @SerialName("rankUp") RANK_UP(
        "Поднять ELO и уровень",
        "chart.line.uptrend.xyaxis",
        Tint.CYAN
    ),
    @SerialName("consistency") CONSISTENCY(
        "Стабильная игра",
        "metronome",
        Tint.BLUE
    ),
    @SerialName("proTeam") PRO_TEAM(
        "Попасть в основу / про-сцену",
        "trophy",
        Tint.CYAN
    ),
    @SerialName("tilt") TILT(
        "Меньше тильта и стресса",
        "flame",
        Tint.ORANGE
    ),
    @SerialName("focus") FOCUS(
        "Концентрация",
        "scope",
        Tint.BLUE
    ),
    @SerialName("sleep") SLEEP(
        "Наладить сон",
        "moon",
        Tint.PURPLE
    ),
    @SerialName("energy") ENERGY(
        "Больше энергии",
        "bolt",
        Tint.YELLOW
    ),
    @SerialName("fitness") FITNESS(
        "Физическая форма",
        "figure.run",
        Tint.GREEN
    ),
    @SerialName("nutrition") NUTRITION(
        "Питание",
        "fork.knife",
        Tint.GREEN
    ),
    @SerialName("screen") SCREEN(
        "Меньше экранного времени",
        "iphone",
        Tint.PINK
    );

    companion object {
        const val MAX_SELECTED = 3
    }
}

@Serializable
enum class PlayerGameRole(val title: String, val subtitle: String) {
    @SerialName("entry") ENTRY(
        "Entry",
        "Первым заходит на точку"
    ),
    @SerialName("awper") AWPER(
        "AWPer",
        "Снайпер команды"
    ),
    @SerialName("support") SUPPORT(
        "Support",
        "Гранаты и размен"
    ),
    @SerialName("igl") IGL(
        "IGL",
        "Капитан, колы по ходу раунда"
    ),
    @SerialName("lurker") LURKER(
        "Lurker",
        "Играет в одиночку, ловит ротации"
    ),
    @SerialName("rifler") RIFLER(
        "Rifler",
        "Основной стрелок"
    ),
    @SerialName("flex") FLEX(
        "Флекс",
        "Играю любую позицию"
    )
}

@Serializable
enum class SkillLevel(val title: String, val subtitle: String) {
    @SerialName("beginner") BEGINNER(
        "Новичок",
        "FACEIT 1–4, учусь основам"
    ),
    @SerialName("amateur") AMATEUR(
        "Любитель",
        "FACEIT 5–7, играю регулярно"
    ),
    @SerialName("semiPro") SEMI_PRO(
        "Полупро",
        "FACEIT 8–10, турниры и праки"
    ),
    @SerialName("pro") PRO(
        "Про",
        "Контракт, лиги и LAN"
    )
}

@Serializable
enum class PlayTime(val title: String) {
    @SerialName("morning") MORNING(
        "Утро"
    ),
    @SerialName("day") DAY(
        "День"
    ),
    @SerialName("evening") EVENING(
        "Вечер"
    ),
    @SerialName("night") NIGHT(
        "Ночь"
    )
}

@Serializable
enum class PlayerMotivation(val title: String) {
    @SerialName("winning") WINNING(
        "Победы и рейтинг"
    ),
    @SerialName("growth") GROWTH(
        "Рост скилла"
    ),
    @SerialName("team") TEAM(
        "Команда и общение"
    ),
    @SerialName("fun") FUN(
        "Удовольствие от игры"
    ),
    @SerialName("career") CAREER(
        "Карьера в киберспорте"
    )
}

/** Competitive-mindset traits measured by the «Игровой характер» test (self-report, not a diagnosis). */
@Serializable
enum class MindsetTrait(val title: String, val shortTitle: String, val icon: String, val tint: Tint, val meaning: String, val playerTip: String, val coachTip: String, val coachStrength: String) {
    @SerialName("composure") COMPOSURE(
        "Хладнокровие",
        "Хладнокр.",
        "snowflake",
        Tint.CYAN,
        "Насколько спокойно принимаешь решения под давлением: клатчи, овертаймы, важные матчи.",
        "Перед клатчем — один медленный выдох и план из двух шагов. Дыхание «квадрат» 4-4-4-4 перед важными матчами.",
        "Тренируйте клатчи 1vX на праках и разбирайте их отдельно; перед важными матчами — короткая рутина подготовки.",
        "Доверяйте решающие раунды и клатч-позиции."
    ),
    @SerialName("resilience") RESILIENCE(
        "Контроль тильта",
        "Антитильт",
        "shield.lefthalf.filled",
        Tint.GREEN,
        "Как быстро отпускаешь ошибки, проигранные раунды и токсичность.",
        "Заведи ритуал сброса после смерти: выдох, «следующий раунд», взгляд на миникарту. Не читай чат в матче.",
        "Не разбирайте ошибки в течение карты; давайте конкретную задачу на следующий раунд. Следите за тильтом после серий поражений.",
        "Опора команды после проигранных раундов — может задавать спокойный тон."
    ),
    @SerialName("focus") FOCUS(
        "Концентрация",
        "Фокус",
        "scope",
        Tint.BLUE,
        "Умение держать внимание весь матч и не отвлекаться.",
        "Телефон — в другую комнату, перерыв 5 минут между картами. Утренняя когнитивная батарея покажет, в какие дни фокус проседает.",
        "Ставьте в важные карты пораньше в дне, делайте перерывы между картами; следите за сном и утомлением в тестах.",
        "Подходит для позиций, где нужна дисциплина и терпение (якорь, лерк)."
    ),
    @SerialName("teamwork") TEAMWORK(
        "Командность",
        "Команда",
        "person.3",
        Tint.YELLOW,
        "Коммуникация, готовность играть на команду и спокойная реакция на ошибки тиммейтов.",
        "Колл в формате «где, сколько, что сделал» — до 3 слов. После ошибки тиммейта — только информация, без оценок.",
        "Договоритесь о формате коммуникации и ролях; хвалите за полезную инфу, а не только за фраги.",
        "Хорошо связывает команду: инфа, размены, поддержка."
    ),
    @SerialName("leadership") LEADERSHIP(
        "Лидерство",
        "Лидерство",
        "flag",
        Tint.ORANGE,
        "Готовность брать решения на себя и вести команду.",
        "Начни с малого: бери колл в одном раунде на половину или предлагай план на тайм-ауте.",
        "Не ставьте на роль IGL без подготовки; давайте зону ответственности — например, мид-раунд колы на одной точке.",
        "Кандидат в IGL или второго капитана."
    ),
    @SerialName("drive") DRIVE(
        "Дисциплина",
        "Дисциплина",
        "dumbbell",
        Tint.PURPLE,
        "Регулярность тренировок, разминка, разбор демок.",
        "Короткий план на неделю в календаре Pulse: 3 тренировки по 45 минут и разбор одной демки.",
        "Дайте понятный план с контрольными точками и отмечайте прогресс — игроку важен видимый результат.",
        "Пример для команды по режиму и подготовке."
    )
}

internal val mindsetQuestions = listOf(
    MindsetQuestion("q1", "В клатче 1 против 2 я думаю спокойно и по плану.", MindsetTrait.COMPOSURE, false),
    MindsetQuestion("q2", "После глупой смерти я быстро переключаюсь на следующий раунд.", MindsetTrait.RESILIENCE, false),
    MindsetQuestion("q3", "Я держу внимание весь матч, даже если он длится больше часа.", MindsetTrait.FOCUS, false),
    MindsetQuestion("q4", "Я даю короткую и точную информацию, даже когда умираю.", MindsetTrait.TEAMWORK, false),
    MindsetQuestion("q5", "Когда раунд разваливается, я беру на себя решение, куда идти.", MindsetTrait.LEADERSHIP, false),
    MindsetQuestion("q6", "Я тренируюсь по плану, даже когда не хочется.", MindsetTrait.DRIVE, false),
    MindsetQuestion("q7", "При счёте 12:12 у меня начинают трястись руки.", MindsetTrait.COMPOSURE, true),
    MindsetQuestion("q8", "Серия проигранных раундов надолго выбивает меня из колеи.", MindsetTrait.RESILIENCE, true),
    MindsetQuestion("q9", "Во время игры я отвлекаюсь на телефон, чат или посторонние мысли.", MindsetTrait.FOCUS, true),
    MindsetQuestion("q10", "Мне проще играть в соло, чем подстраиваться под команду.", MindsetTrait.TEAMWORK, true),
    MindsetQuestion("q11", "Мне некомфортно давать указания другим игрокам.", MindsetTrait.LEADERSHIP, true),
    MindsetQuestion("q12", "Разминку перед игрой я часто пропускаю.", MindsetTrait.DRIVE, true),
    MindsetQuestion("q13", "Важный матч или LAN не мешает мне играть на своём уровне.", MindsetTrait.COMPOSURE, false),
    MindsetQuestion("q14", "Токсичный тиммейт или соперник почти не влияет на мою игру.", MindsetTrait.RESILIENCE, false),
    MindsetQuestion("q15", "Я слежу за звуками, таймером и экономикой соперника, не теряя прицел.", MindsetTrait.FOCUS, false),
    MindsetQuestion("q16", "Я готов сыграть неудобную для себя роль, если это нужно команде.", MindsetTrait.TEAMWORK, false),
    MindsetQuestion("q17", "Я замечаю, когда команда теряет настрой, и стараюсь её поднять.", MindsetTrait.LEADERSHIP, false),
    MindsetQuestion("q18", "Я разбираю свои демки и записываю, что улучшить.", MindsetTrait.DRIVE, false),
    MindsetQuestion("q19", "Под давлением я спешу и делаю необдуманные пики.", MindsetTrait.COMPOSURE, true),
    MindsetQuestion("q20", "После поражения я долго прокручиваю ошибки и злюсь.", MindsetTrait.RESILIENCE, true),
    MindsetQuestion("q21", "К концу второй карты мои реакции заметно хуже.", MindsetTrait.FOCUS, true),
    MindsetQuestion("q22", "Когда тиммейт ошибается, я раздражаюсь и высказываю это в игре.", MindsetTrait.TEAMWORK, true),
    MindsetQuestion("q23", "Я предпочитаю, чтобы решения в игре принимал кто-то другой.", MindsetTrait.LEADERSHIP, true),
    MindsetQuestion("q24", "Я забрасываю тренировки, если не вижу быстрого прогресса.", MindsetTrait.DRIVE, true)
)

internal fun leadingTraitSummary(trait: MindsetTrait): String = when (trait) {
    MindsetTrait.COMPOSURE -> "Спокойно принимает решения под давлением: клатчи, овертаймы, важные матчи."
    MindsetTrait.RESILIENCE -> "Быстро отпускает ошибки и проигранные раунды."
    MindsetTrait.FOCUS -> "Держит внимание весь матч и не отвлекается."
    MindsetTrait.TEAMWORK -> "Хорошая коммуникация, готов играть на команду."
    MindsetTrait.LEADERSHIP -> "Готов брать решения на себя и вести команду."
    MindsetTrait.DRIVE -> "Тренируется регулярно и по плану."
}
