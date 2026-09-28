package com.mininetworks.game.game

/**
 * What the calendar brings week by week (docs/TOP100.md G3, docs/BALANCING.md): each cable technology, device and radio
 * in its [CableType.unlockWeek], [Device.unlockWeek] and [RadioType.unlockWeek], the first server of each service in
 * its [Service.serverWeek], then a server of a random service every second week from
 * [World.Tuning.RANDOM_SERVERS_FROM]; incidents start and grow by the weeks played ([Incidents.countIn]).
 * [World] takes its week changes from here, so the schedule tested is the one played.
 */
object WeekSchedule {
    fun cables(week: Int) = CableType.entries.filter { it.unlockWeek == week }

    fun devices(week: Int) = Device.entries.filter { it.unlockWeek == week }

    fun radios(week: Int) = RadioType.entries.filter { it.unlockWeek == week }

    /** Mobile generations new in [week]; the one that comes with the cell tower is announced as the tower. */
    fun cellGenerations(week: Int) = CellGeneration.entries.filter { it.unlockWeek == week && week > RadioType.CELL.unlockWeek }

    /** The service whose first server appears in [week], if any. */
    fun firstServer(week: Int) = Service.entries.firstOrNull { it.serverWeek == week }

    /** True if [week] brings a server of a random service: from [World.Tuning.RANDOM_SERVERS_FROM] on, every second week without a [firstServer]. */
    fun randomServer(week: Int) = firstServer(week) == null && week >= World.Tuning.RANDOM_SERVERS_FROM && week % 2 == 0

    /** True if the week with [weeksPlayed] brings incidents for the first time or more of them than the week before. */
    fun moreIncidents(weeksPlayed: Int) = Incidents.countIn(weeksPlayed) > Incidents.countIn(weeksPlayed - 1)

    /** Everything [week] brings in a game that started in week 1 (the first scenery), see [WeekContent]. */
    fun content(week: Int) = WeekContent(
        week, cables(week), devices(week), radios(week), firstServer(week), randomServer(week), moreIncidents(week), cellGenerations(week),
    )
}

/**
 * What one week brings: new cable technologies, devices and radios (tech and devices), the first server of a
 * [server] or a server of a random service ([randomServer]) (services), and [moreIncidents] (events).
 */
data class WeekContent(
    val week: Int,
    val cables: List<CableType>,
    val devices: List<Device>,
    val radios: List<RadioType>,
    val server: Service?,
    val randomServer: Boolean,
    val moreIncidents: Boolean,
    val cellGenerations: List<CellGeneration> = emptyList(),
) {
    /** True if the week brings nothing new at all. */
    val isEmpty get() = cables.isEmpty() && devices.isEmpty() && radios.isEmpty() && server == null && !randomServer && !moreIncidents &&
        cellGenerations.isEmpty()
}
