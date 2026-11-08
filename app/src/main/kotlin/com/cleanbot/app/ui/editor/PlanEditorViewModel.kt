package com.cleanbot.app.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleanbot.app.data.model.Cell
import com.cleanbot.app.data.model.CleaningPlan
import com.cleanbot.app.data.model.Zone
import com.cleanbot.app.data.repository.RobotRepository
import com.cleanbot.app.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Локальный эскиз плана уборки: независим от GET/PUT, изменения живут только
 * на экране редактора до нажатия «Сохранить» (PUT /plan) или «Отмена» (сброс).
 */
class PlanEditorViewModel(
    private val robotId: String,
    private val repository: RobotRepository = AppContainer.robotRepository
) : ViewModel() {

    private val _sketch = MutableStateFlow<CleaningPlan?>(null)
    val sketch: StateFlow<CleaningPlan?> = _sketch.asStateFlow()

    /** Эскиз, загруженный с сервера: с ним сравниваем, есть ли несохранённые изменения. */
    private var original: CleaningPlan? = null

    private val _activeZoneIndex = MutableStateFlow(-1)
    val activeZoneIndex: StateFlow<Int> = _activeZoneIndex.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    /** true, если после успешного сохранения нужно вернуться на экран робота. */
    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    val hasUnsavedChanges: Boolean
        get() = _sketch.value != original

    fun load() {
        viewModelScope.launch {
            runCatching { repository.plan(robotId) }.onSuccess { plan ->
                // route в эскизе не нужен: PUT его игнорирует, а пересчитывает симулятор
                val clean = plan.copy(route = emptyList())
                original = clean
                _sketch.value = clean
                if (clean.zones.isEmpty()) _activeZoneIndex.value = -1
                else _activeZoneIndex.value = 0
            }.onFailure { _message.value = "Не удалось загрузить план: ${it.message}" }
        }
    }

    fun setActiveZone(index: Int) {
        _activeZoneIndex.value = index
    }

    /**
     * Тап по клетке: закрасить/снять в активной зоне.
     * Поведение при тапе на клетку другой зоны — «не вырезать»: тап игнорируется,
     * пользователю показывается снекбар «Клетка занята зоной X». Клетки других
     * зон не затрагиваются; чтобы перерисовать — удалите зону целиком.
     */
    fun toggleCell(x: Int, y: Int) {
        val plan = _sketch.value ?: return
        val activeIndex = _activeZoneIndex.value
        if (activeIndex !in plan.zones.indices) {
            _message.value = "Сначала выберите или создайте зону"
            return
        }
        val cell = Cell(x, y)
        val owner = plan.zones.indexOfFirst { zone -> zone.cells.contains(cell) }
        if (owner != -1 && owner != activeIndex) {
            _message.value = "Клетка занята зоной «${plan.zones[owner].name}»"
            return
        }
        val active = plan.zones[activeIndex]
        val updatedZones = plan.zones.toMutableList()
        if (owner == activeIndex) {
            updatedZones[activeIndex] = active.copy(cells = active.cells - cell)
        } else {
            updatedZones[activeIndex] = active.copy(cells = active.cells + cell)
        }
        _sketch.value = plan.copy(zones = updatedZones)
    }

    /** Создаёт новую пустую зону, делает её активной, возвращает её индекс. */
    fun createZone(name: String? = null): Int {
        val plan = _sketch.value ?: return -1
        val nextId = (plan.zones.maxOfOrNull { it.id } ?: 0) + 1
        val zoneName = name?.trim().takeUnless { it.isNullOrEmpty() } ?: "Зона ${plan.zones.size + 1}"
        val zones = plan.zones + Zone(nextId, zoneName, emptyList())
        _sketch.value = plan.copy(zones = zones)
        val index = zones.lastIndex
        _activeZoneIndex.value = index
        return index
    }

    fun deleteZone(index: Int) {
        val plan = _sketch.value ?: return
        if (index !in plan.zones.indices) return
        val zones = plan.zones.toMutableList()
        zones.removeAt(index)
        _sketch.value = plan.copy(zones = zones)
        _activeZoneIndex.value = when {
            zones.isEmpty() -> -1
            _activeZoneIndex.value >= zones.size -> zones.lastIndex
            else -> _activeZoneIndex.value
        }
    }

    fun renameZone(index: Int, name: String) {
        val plan = _sketch.value ?: return
        if (index !in plan.zones.indices) return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val zones = plan.zones.toMutableList()
        zones[index] = zones[index].copy(name = trimmed)
        _sketch.value = plan.copy(zones = zones)
    }

    /** PUT эскиза; при ошибке валидации кладёт текст сервера в message. */
    fun save() {
        val plan = _sketch.value ?: return
        viewModelScope.launch {
            _saving.value = true
            runCatching { repository.updatePlan(robotId, plan) }
                .onSuccess { error ->
                    if (error == null) {
                        original = plan
                        _saved.value = true
                    } else {
                        _message.value = error
                    }
                }
                .onFailure { _message.value = "Не удалось сохранить план: ${it.message}" }
            _saving.value = false
        }
    }

    /** Отмена: эскиз возвращается к состоянию с сервера. */
    fun reset() {
        _sketch.value = original
        _activeZoneIndex.value = original?.zones?.indexOfFirst { true } ?: -1
        _message.value = null
    }

    fun clearMessage() {
        _message.value = null
    }
}
