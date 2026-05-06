package com.dolovetime.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Context.dataStore by preferencesDataStore(name = "secure_settings")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        val db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "dolovetime.db")
            .fallbackToDestructiveMigration()
            .build()

        setContent {
            MaterialTheme {
                val vm: MainViewModel = viewModel(factory = MainViewModelFactory(db, PinManager(dataStore)))
                AppRoot(vm)
            }
        }
    }
}

@Entity(tableName = "partners")
data class Partner(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nickname: String,
    val note: String = ""
)

@Entity(
    tableName = "events",
    foreignKeys = [ForeignKey(entity = Partner::class, parentColumns = ["id"], childColumns = ["partnerId"], onDelete = ForeignKey.SET_NULL)]
)
data class Event(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val partnerId: Long?,
    val location: String,
    val method: String,
    val startMillis: Long,
    val endMillis: Long,
    val note: String = ""
)

data class EventWithPartner(
    val id: Long,
    val partnerId: Long?,
    val partnerName: String?,
    val location: String,
    val method: String,
    val startMillis: Long,
    val endMillis: Long,
    val note: String
)

data class TimeStats(val count: Int, val totalMinutes: Long)
data class LocationStat(val location: String, val count: Int)
data class SummaryStats(
    val day: TimeStats = TimeStats(0, 0),
    val week: TimeStats = TimeStats(0, 0),
    val month: TimeStats = TimeStats(0, 0),
    val topLocations: List<LocationStat> = emptyList()
)

@Dao
interface AppDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPartner(partner: Partner)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: Event)

    @Update
    suspend fun updateEvent(event: Event)

    @Delete
    suspend fun deleteEvent(event: Event)

    @Query("SELECT * FROM events WHERE id = :id LIMIT 1")
    suspend fun findEventById(id: Long): Event?

    @Query("SELECT * FROM partners ORDER BY id DESC")
    fun partnersFlow(): kotlinx.coroutines.flow.Flow<List<Partner>>

    @Query(
        """
        SELECT e.id, e.partnerId, p.nickname AS partnerName, e.location, e.method, e.startMillis, e.endMillis, e.note
        FROM events e
        LEFT JOIN partners p ON p.id = e.partnerId
        ORDER BY e.startMillis DESC
        """
    )
    fun eventsFlow(): kotlinx.coroutines.flow.Flow<List<EventWithPartner>>
}

@Database(entities = [Partner::class, Event::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() { abstract fun dao(): AppDao }

class PinManager(private val store: androidx.datastore.core.DataStore<Preferences>) {
    private val pinHashKey = stringPreferencesKey("pin_hash")
    private val autoLockMinutesKey = longPreferencesKey("auto_lock_minutes")
    suspend fun hasPin(): Boolean = store.data.first()[pinHashKey] != null
    suspend fun verifyPin(rawPin: String): Boolean = store.data.first()[pinHashKey] == rawPin.sha256()
    suspend fun setupPin(rawPin: String) { store.edit { it[pinHashKey] = rawPin.sha256(); if (it[autoLockMinutesKey] == null) it[autoLockMinutesKey] = 1L } }
    suspend fun getAutoLockMinutes(): Long = store.data.first()[autoLockMinutesKey] ?: 1L
    suspend fun setAutoLockMinutes(minutes: Long) { store.edit { it[autoLockMinutesKey] = minutes.coerceIn(0L, 120L) } }
}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256").digest(toByteArray()).joinToString("") { "%02x".format(it) }


data class UiState(
    val unlocked: Boolean = false,
    val needsPinSetup: Boolean = true,
    val autoLockMinutes: Long = 1,
    val partners: List<Partner> = emptyList(),
    val events: List<EventWithPartner> = emptyList(),
    val stats: SummaryStats = SummaryStats()
)

class MainViewModel(private val db: AppDatabase, private val pinManager: PinManager) : ViewModel() {
    private val unlocked = MutableStateFlow(false)
    private val needsPinSetup = MutableStateFlow(true)
    private val autoLockMinutes = MutableStateFlow(1L)
    private var lastBackgroundAt: Long? = null

    val state: StateFlow<UiState> = kotlinx.coroutines.flow.combine(
        unlocked, needsPinSetup, autoLockMinutes, db.dao().partnersFlow(), db.dao().eventsFlow()
    ) { un, setup, lockMinutes, partners, events ->
        UiState(unlocked = un, needsPinSetup = setup, autoLockMinutes = lockMinutes, partners = partners, events = events, stats = computeStats(events))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init { viewModelScope.launch { needsPinSetup.value = !pinManager.hasPin(); autoLockMinutes.value = pinManager.getAutoLockMinutes() } }

    fun setupPin(pin: String) { if (pin.length < 4) return; viewModelScope.launch { pinManager.setupPin(pin); needsPinSetup.value = false; unlocked.value = true } }
    fun unlock(pin: String) { viewModelScope.launch { unlocked.value = pinManager.verifyPin(pin) } }
    fun addPartner(name: String, note: String) { if (name.isBlank()) return; viewModelScope.launch { db.dao().insertPartner(Partner(nickname = name.trim(), note = note.trim())) } }
    fun addEvent(partnerId: Long?, location: String, method: String, durationMinutes: Long, note: String, endMillis: Long) {
        if (location.isBlank() || method.isBlank() || durationMinutes <= 0) return
        val start = endMillis - durationMinutes * 60_000
        viewModelScope.launch { db.dao().insertEvent(Event(partnerId = partnerId, location = location.trim(), method = method.trim(), startMillis = start, endMillis = endMillis, note = note.trim())) }
    }
    fun updateEvent(id: Long, partnerId: Long?, location: String, method: String, durationMinutes: Long, note: String, endMillis: Long) {
        if (location.isBlank() || method.isBlank() || durationMinutes <= 0) return
        viewModelScope.launch {
            val old = db.dao().findEventById(id) ?: return@launch
            val start = endMillis - durationMinutes * 60_000
            db.dao().updateEvent(old.copy(partnerId = partnerId, location = location, method = method, startMillis = start, endMillis = endMillis, note = note))
        }
    }
    fun deleteEvent(id: Long) { viewModelScope.launch { db.dao().findEventById(id)?.let { db.dao().deleteEvent(it) } } }
    fun setAutoLockMinutes(minutes: Long) { viewModelScope.launch { pinManager.setAutoLockMinutes(minutes); autoLockMinutes.value = pinManager.getAutoLockMinutes() } }
    fun onAppBackground() { lastBackgroundAt = System.currentTimeMillis() }
    fun onAppForeground() { val since = lastBackgroundAt ?: return; if (autoLockMinutes.value == 0L || System.currentTimeMillis() - since >= autoLockMinutes.value * 60_000) unlocked.value = false }

    private fun computeStats(events: List<EventWithPartner>): SummaryStats {
        val now = System.currentTimeMillis()
        fun bucket(days: Long): TimeStats {
            val start = now - days * 24L * 60L * 60L * 1000L
            val filtered = events.filter { it.startMillis >= start }
            return TimeStats(filtered.size, filtered.sumOf { (it.endMillis - it.startMillis).coerceAtLeast(0) / 60000 })
        }
        val topLocations = events.groupBy { it.location.ifBlank { "未知" } }.map { LocationStat(it.key, it.value.size) }.sortedByDescending { it.count }.take(5)
        return SummaryStats(bucket(1), bucket(7), bucket(30), topLocations)
    }
}

class MainViewModelFactory(private val db: AppDatabase, private val pinManager: PinManager) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(db, pinManager) as T
}

@Composable
fun AppRoot(vm: MainViewModel) {
    val state by vm.state.collectAsStateCompat()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) vm.onAppBackground() else if (e == Lifecycle.Event.ON_START) vm.onAppForeground() }
        lifecycle.addObserver(obs); onDispose { lifecycle.removeObserver(obs) }
    }
    when {
        state.needsPinSetup -> PinSetupScreen(vm::setupPin)
        !state.unlocked -> UnlockScreen(vm::unlock)
        else -> HomeScreen(state, vm::addPartner, vm::addEvent, vm::updateEvent, vm::deleteEvent, vm::setAutoLockMinutes)
    }
}

@Composable fun PinSetupScreen(onSetup: (String) -> Unit) { var pin by remember { mutableStateOf("") }; Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center) { Text("首次使用请设置 4 位以上 PIN"); Spacer(Modifier.height(12.dp)); OutlinedTextField(pin, { pin = it }, label = { Text("PIN") }); Spacer(Modifier.height(12.dp)); Button({ onSetup(pin) }) { Text("保存并进入") } } }
@Composable fun UnlockScreen(onUnlock: (String) -> Unit) { var pin by remember { mutableStateOf("") }; Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center) { Text("输入 PIN 解锁"); Spacer(Modifier.height(12.dp)); OutlinedTextField(pin, { pin = it }, label = { Text("PIN") }); Spacer(Modifier.height(12.dp)); Button({ onUnlock(pin) }) { Text("解锁") } } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: UiState,
    onAddPartner: (String, String) -> Unit,
    onAddEvent: (Long?, String, String, Long, String, Long) -> Unit,
    onUpdateEvent: (Long, Long?, String, String, Long, String, Long) -> Unit,
    onDeleteEvent: (Long) -> Unit,
    onSetAutoLockMinutes: (Long) -> Unit
) {
    val context = LocalContext.current
    val methods = listOf("体外", "内射", "戴套", "口", "手", "情趣用品", "其它")
    val tabs = listOf("记录", "统计", "对象管理", "隐私设置")

    var partnerName by remember { mutableStateOf("") }
    var partnerNote by remember { mutableStateOf("") }
    var selectedPartnerId by remember { mutableStateOf<Long?>(null) }
    var partnerExpanded by remember { mutableStateOf(false) }
    var location by remember { mutableStateOf("") }
    var method by remember { mutableStateOf(methods.first()) }
    var methodExpanded by remember { mutableStateOf(false) }
    var duration by remember { mutableStateOf("30") }
    var eventNote by remember { mutableStateOf("") }
    var autoLockInput by remember(state.autoLockMinutes) { mutableStateOf(state.autoLockMinutes.toString()) }
    var timerRunning by remember { mutableStateOf(false) }
    var timerSeconds by remember { mutableStateOf(0L) }
    var editingEventId by remember { mutableStateOf<Long?>(null) }
    var currentTab by remember { mutableStateOf(0) }

    var calendarMonth by remember { mutableStateOf(YearMonth.now()) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var calendarExpanded by remember { mutableStateOf(false) }
    var startTimeText by remember { mutableStateOf(java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val ok = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true || granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (ok) location = readCurrentAddress(context) ?: location
    }

    LaunchedEffect(timerRunning) {
        while (timerRunning) {
            delay(1000); timerSeconds += 1; duration = ((timerSeconds / 60).coerceAtLeast(1)).toString()
        }
    }

    val selectedPartnerLabel = state.partners.firstOrNull { it.id == selectedPartnerId }?.nickname ?: "单身/自己"
    val eventsByDate = state.events.groupBy { millisToLocalDate(it.startMillis) }
    val selectedDayEvents = eventsByDate[selectedDate].orEmpty()
    val recent7Days = (0L..6L).map { LocalDate.now().minusDays(it) }

    Scaffold(topBar = { TopAppBar(title = { Text("doLoveTime") }) }) { p ->
        Column(Modifier.fillMaxSize().padding(p)) {
            TabRow(selectedTabIndex = currentTab) {
                tabs.forEachIndexed { idx, title ->
                    Tab(selected = currentTab == idx, onClick = { currentTab = idx }, text = { Text(title) })
                }
            }

            when (currentTab) {
                0 -> LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("日历记录", fontWeight = FontWeight.Bold)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    recent7Days.reversed().forEach { d ->
                                        val cnt = eventsByDate[d]?.size ?: 0
                                        Button(onClick = { selectedDate = d }) { Text("${d.dayOfMonth}${if (cnt > 0) "*$cnt" else ""}") }
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("已选日期：$selectedDate")
                                    Button(onClick = { calendarExpanded = !calendarExpanded }) { Text(if (calendarExpanded) "收起月历" else "展开月历") }
                                }
                                if (calendarExpanded) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button({ calendarMonth = calendarMonth.minusMonths(1) }) { Text("<") }
                                        Text(calendarMonth.format(DateTimeFormatter.ofPattern("yyyy-MM")))
                                        Button({ calendarMonth = calendarMonth.plusMonths(1) }) { Text(">") }
                                    }
                                    CalendarGrid(calendarMonth, selectedDate, eventsByDate, onSelect = { selectedDate = it })
                                }
                                Text("${selectedDate} 记录：${selectedDayEvents.size}")
                                if (selectedDayEvents.isEmpty()) Text("当天无记录")
                            }
                        }
                    }

                    items(selectedDayEvents) { e ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("对象：${e.partnerName ?: "自己"}")
                                Text("地点：${e.location}")
                                val startTime = java.time.Instant.ofEpochMilli(e.startMillis).atZone(ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
                                Text("开始：$startTime | 方式：${e.method} | 时长：${(e.endMillis - e.startMillis) / 60000} 分")
                                if (e.note.isNotBlank()) Text("备注：${e.note}")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = {
                                        editingEventId = e.id
                                        selectedPartnerId = e.partnerId
                                        location = e.location
                                        method = e.method
                                        duration = (((e.endMillis - e.startMillis) / 60000).coerceAtLeast(1)).toString()
                                        eventNote = e.note
                                        selectedDate = millisToLocalDate(e.startMillis)
                                        startTimeText = java.time.Instant.ofEpochMilli(e.startMillis).atZone(ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
                                    }) { Text("编辑") }
                                    Button(onClick = { onDeleteEvent(e.id) }) { Text("删除") }
                                }
                            }
                        }
                    }

                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(if (editingEventId == null) "新增记录" else "编辑记录", fontWeight = FontWeight.Bold)
                                Text("记录日期：$selectedDate")
                                OutlinedTextField(startTimeText, { startTimeText = it }, label = { Text("开始时间(HH:mm)") })

                                PickerField("对象", selectedPartnerLabel) { partnerExpanded = true }
                                DropdownMenu(expanded = partnerExpanded, onDismissRequest = { partnerExpanded = false }) {
                                    DropdownMenuItem(text = { Text("单身/自己") }, onClick = { selectedPartnerId = null; partnerExpanded = false })
                                    state.partners.forEach { p2 -> DropdownMenuItem(text = { Text(p2.nickname) }, onClick = { selectedPartnerId = p2.id; partnerExpanded = false }) }
                                }

                                OutlinedTextField(location, { location = it }, label = { Text("地点") })
                                Button(onClick = {
                                    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                                    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                                    if (fine || coarse) location = readCurrentAddress(context) ?: location else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                                }) { Text("获取当前位置地址") }

                                PickerField("方式", method) { methodExpanded = true }
                                DropdownMenu(expanded = methodExpanded, onDismissRequest = { methodExpanded = false }) {
                                    methods.forEach { m -> DropdownMenuItem(text = { Text(m) }, onClick = { method = m; methodExpanded = false }) }
                                }

                                OutlinedTextField(duration, { duration = it }, label = { Text("时长(分钟)") })
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button({ timerRunning = true }) { Text("开始") }
                                    Button({ timerRunning = false }) { Text("停止") }
                                    Button({ timerRunning = false; timerSeconds = 0; duration = "1" }) { Text("重置") }
                                }
                                Text("计时：${timerSeconds / 60}分 ${timerSeconds % 60}秒")

                                OutlinedTextField(eventNote, { eventNote = it }, label = { Text("备注") })
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = {
                                        val d = duration.toLongOrNull() ?: 0
                                        val start = parseSelectedDateTimeMillis(selectedDate, startTimeText)
                                        val end = start + d * 60_000
                                        if (editingEventId == null) onAddEvent(selectedPartnerId, location, method, d, eventNote, end)
                                        else onUpdateEvent(editingEventId!!, selectedPartnerId, location, method, d, eventNote, end)
                                        editingEventId = null
                                        location = ""
                                        method = methods.first()
                                        duration = "30"
                                        eventNote = ""
                                        timerSeconds = 0
                                        timerRunning = false
                                        startTimeText = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
                                    }) { Text(if (editingEventId == null) "保存记录" else "保存修改") }
                                    if (editingEventId != null) Button(onClick = { editingEventId = null }) { Text("取消编辑") }
                                }
                            }
                        }
                    }
                }
                1 -> LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("统计分析", fontWeight = FontWeight.Bold)
                                Text("本周：${state.stats.week.count} 次 / ${state.stats.week.totalMinutes} 分钟")
                                Text("本月：${state.stats.month.count} 次 / ${state.stats.month.totalMinutes} 分钟")
                                val yearEvents = state.events.filter {
                                    java.time.Instant.ofEpochMilli(it.startMillis).atZone(ZoneId.systemDefault()).year == LocalDate.now().year
                                }
                                Text("本年：${yearEvents.size} 次 / ${yearEvents.sumOf { (it.endMillis - it.startMillis) / 60000 }} 分钟")
                                Text("地点排行：")
                                if (state.stats.topLocations.isEmpty()) Text("暂无数据")
                                state.stats.topLocations.forEachIndexed { i, l -> Text("${i + 1}. ${l.location} (${l.count}次)") }
                            }
                        }
                    }
                }
                2 -> LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("对象管理", fontWeight = FontWeight.Bold)
                                OutlinedTextField(partnerName, { partnerName = it }, label = { Text("对象昵称") })
                                OutlinedTextField(partnerNote, { partnerNote = it }, label = { Text("备注") })
                                Button({ onAddPartner(partnerName, partnerNote); partnerName = ""; partnerNote = "" }) { Text("添加对象") }
                                Text("对象列表(${state.partners.size})", fontWeight = FontWeight.Bold)
                                if (state.partners.isEmpty()) Text("暂无对象") else state.partners.forEach { Text("- ${it.nickname}${if (it.note.isNotBlank()) "（${it.note}）" else ""}") }
                            }
                        }
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("隐私设置", fontWeight = FontWeight.Bold)
                                OutlinedTextField(autoLockInput, { autoLockInput = it }, label = { Text("自动锁定(分钟)") })
                                Button({ onSetAutoLockMinutes(autoLockInput.toLongOrNull() ?: 1L) }) { Text("保存") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarGrid(
    month: YearMonth,
    selected: LocalDate,
    eventsByDate: Map<LocalDate, List<EventWithPartner>>,
    onSelect: (LocalDate) -> Unit
) {
    val first = month.atDay(1)
    val startOffset = first.dayOfWeek.value % 7
    val total = month.lengthOfMonth()
    val days = (1..total).map { month.atDay(it) }
    val cells = List(startOffset) { null } + days
    val rows = (cells.size + 6) / 7

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(rows) { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(7) { c ->
                    val idx = r * 7 + c
                    val d = cells.getOrNull(idx)
                    val isSelected = d == selected
                    Card(
                        modifier = Modifier.width(42.dp).height(42.dp).clickable(enabled = d != null) { if (d != null) onSelect(d) },
                        border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                            if (d != null) {
                                val cnt = eventsByDate[d]?.size ?: 0
                                Text(if (cnt > 0) "${d.dayOfMonth}*" else d.dayOfMonth.toString())
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun millisToLocalDate(ms: Long): LocalDate = java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

@Composable
private fun PickerField(label: String, value: String, onClick: () -> Unit) {
    Box {
        OutlinedTextField(value = value, onValueChange = {}, readOnly = true, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
        Box(modifier = Modifier.matchParentSize().clickable { onClick() })
    }
}

private fun parseSelectedDateTimeMillis(date: LocalDate, timeText: String): Long {
    val t = try { LocalTime.parse(timeText, DateTimeFormatter.ofPattern("HH:mm")) } catch (_: Exception) { LocalTime.now() }
    return LocalDateTime.of(date, t).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

private fun readCurrentAddress(context: Context): String? {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!fine && !coarse) return null
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val providers = lm.getProviders(true)
    for (provider in providers) {
        val loc = try { lm.getLastKnownLocation(provider) } catch (_: Exception) { null }
        if (loc != null) {
            return try {
                val geocoder = Geocoder(context, Locale.CHINA)
                val list = geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
                val addr = list?.firstOrNull()?.getAddressLine(0)
                addr ?: "${"%.5f".format(loc.latitude)}, ${"%.5f".format(loc.longitude)}"
            } catch (_: Exception) {
                "${"%.5f".format(loc.latitude)}, ${"%.5f".format(loc.longitude)}"
            }
        }
    }
    return null
}

@Composable
private fun <T> StateFlow<T>.collectAsStateCompat(): androidx.compose.runtime.State<T> {
    val s = remember { mutableStateOf(value) }
    LaunchedEffect(this) { collect { s.value = it } }
    return s
}
