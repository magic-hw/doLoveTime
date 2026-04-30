package com.dolovetime.app

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.security.MessageDigest

private val Context.dataStore by preferencesDataStore(name = "secure_settings")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        val db = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "dolovetime.db"
        ).fallbackToDestructiveMigration().build()

        val pinManager = PinManager(dataStore)

        setContent {
            MaterialTheme {
                val vm: MainViewModel = viewModel(
                    factory = MainViewModelFactory(db, pinManager)
                )
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
    foreignKeys = [
        ForeignKey(
            entity = Partner::class,
            parentColumns = ["id"],
            childColumns = ["partnerId"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
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

data class TimeStats(
    val count: Int,
    val totalMinutes: Long
)

data class LocationStat(
    val location: String,
    val count: Int
)

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

@Database(entities = [Partner::class, Event::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao
}

class PinManager(private val store: androidx.datastore.core.DataStore<Preferences>) {
    private val pinHashKey = stringPreferencesKey("pin_hash")
    private val autoLockMinutesKey = longPreferencesKey("auto_lock_minutes")

    suspend fun hasPin(): Boolean = store.data.first()[pinHashKey] != null

    suspend fun verifyPin(rawPin: String): Boolean {
        val saved = store.data.first()[pinHashKey] ?: return false
        return saved == rawPin.sha256()
    }

    suspend fun setupPin(rawPin: String) {
        store.edit {
            it[pinHashKey] = rawPin.sha256()
            if (it[autoLockMinutesKey] == null) {
                it[autoLockMinutesKey] = 1L
            }
        }
    }

    suspend fun getAutoLockMinutes(): Long = store.data.first()[autoLockMinutesKey] ?: 1L

    suspend fun setAutoLockMinutes(minutes: Long) {
        store.edit { it[autoLockMinutesKey] = minutes.coerceIn(0L, 120L) }
    }
}

private fun String.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}

data class UiState(
    val unlocked: Boolean = false,
    val needsPinSetup: Boolean = true,
    val autoLockMinutes: Long = 1,
    val partners: List<Partner> = emptyList(),
    val events: List<EventWithPartner> = emptyList(),
    val stats: SummaryStats = SummaryStats()
)

class MainViewModel(
    private val db: AppDatabase,
    private val pinManager: PinManager
) : ViewModel() {
    private val unlocked = MutableStateFlow(false)
    private val needsPinSetup = MutableStateFlow(true)
    private val autoLockMinutes = MutableStateFlow(1L)
    private var lastBackgroundAt: Long? = null

    val state: StateFlow<UiState> = kotlinx.coroutines.flow.combine(
        unlocked,
        needsPinSetup,
        autoLockMinutes,
        db.dao().partnersFlow(),
        db.dao().eventsFlow()
    ) { un, setup, lockMinutes, partners, events ->
        UiState(
            unlocked = un,
            needsPinSetup = setup,
            autoLockMinutes = lockMinutes,
            partners = partners,
            events = events,
            stats = computeStats(events)
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        viewModelScope.launch {
            needsPinSetup.value = !pinManager.hasPin()
            autoLockMinutes.value = pinManager.getAutoLockMinutes()
        }
    }

    fun setupPin(pin: String) {
        if (pin.length < 4) return
        viewModelScope.launch {
            pinManager.setupPin(pin)
            autoLockMinutes.value = pinManager.getAutoLockMinutes()
            needsPinSetup.value = false
            unlocked.value = true
        }
    }

    fun unlock(pin: String) {
        viewModelScope.launch {
            unlocked.value = pinManager.verifyPin(pin)
        }
    }

    fun addPartner(name: String, note: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            db.dao().insertPartner(Partner(nickname = name.trim(), note = note.trim()))
        }
    }

    fun addEvent(
        partnerId: Long?,
        location: String,
        method: String,
        durationMinutes: Long,
        note: String
    ) {
        if (location.isBlank() || method.isBlank() || durationMinutes <= 0) return
        val end = System.currentTimeMillis()
        val start = end - durationMinutes * 60_000
        viewModelScope.launch {
            db.dao().insertEvent(
                Event(
                    partnerId = partnerId,
                    location = location.trim(),
                    method = method.trim(),
                    startMillis = start,
                    endMillis = end,
                    note = note.trim()
                )
            )
        }
    }

    fun setAutoLockMinutes(minutes: Long) {
        viewModelScope.launch {
            pinManager.setAutoLockMinutes(minutes)
            autoLockMinutes.value = pinManager.getAutoLockMinutes()
        }
    }

    fun onAppBackground() {
        lastBackgroundAt = System.currentTimeMillis()
    }

    fun onAppForeground() {
        val since = lastBackgroundAt ?: return
        val thresholdMs = autoLockMinutes.value * 60_000
        if (thresholdMs == 0L || System.currentTimeMillis() - since >= thresholdMs) {
            unlocked.value = false
        }
    }

    private fun computeStats(events: List<EventWithPartner>): SummaryStats {
        val now = System.currentTimeMillis()
        fun bucket(days: Long): TimeStats {
            val start = now - days * 24L * 60L * 60L * 1000L
            val filtered = events.filter { it.startMillis >= start }
            val totalMinutes = filtered.sumOf { (it.endMillis - it.startMillis).coerceAtLeast(0) / 60000 }
            return TimeStats(filtered.size, totalMinutes)
        }

        val topLocations = events
            .groupBy { it.location.ifBlank { "未知" } }
            .map { LocationStat(it.key, it.value.size) }
            .sortedByDescending { it.count }
            .take(5)

        return SummaryStats(
            day = bucket(1),
            week = bucket(7),
            month = bucket(30),
            topLocations = topLocations
        )
    }
}

class MainViewModelFactory(
    private val db: AppDatabase,
    private val pinManager: PinManager
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return MainViewModel(db, pinManager) as T
    }
}

@Composable
fun AppRoot(vm: MainViewModel) {
    val state by vm.state.collectAsStateCompat()
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> vm.onAppBackground()
                Lifecycle.Event.ON_START -> vm.onAppForeground()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    when {
        state.needsPinSetup -> PinSetupScreen(onSetup = vm::setupPin)
        !state.unlocked -> UnlockScreen(onUnlock = vm::unlock)
        else -> HomeScreen(
            state = state,
            onAddPartner = vm::addPartner,
            onAddEvent = vm::addEvent,
            onSetAutoLockMinutes = vm::setAutoLockMinutes
        )
    }
}

@Composable
fun PinSetupScreen(onSetup: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("首次使用请设置 4 位以上 PIN", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = pin, onValueChange = { pin = it }, label = { Text("PIN") })
        Spacer(Modifier.height(12.dp))
        Button(onClick = { onSetup(pin) }) { Text("保存并进入") }
    }
}

@Composable
fun UnlockScreen(onUnlock: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("输入 PIN 解锁", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = pin, onValueChange = { pin = it }, label = { Text("PIN") })
        Spacer(Modifier.height(12.dp))
        Button(onClick = { onUnlock(pin) }) { Text("解锁") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: UiState,
    onAddPartner: (String, String) -> Unit,
    onAddEvent: (Long?, String, String, Long, String) -> Unit,
    onSetAutoLockMinutes: (Long) -> Unit
) {
    var partnerName by remember { mutableStateOf("") }
    var partnerNote by remember { mutableStateOf("") }

    var selectedPartnerId by remember { mutableStateOf<Long?>(null) }
    var dropdownExpanded by remember { mutableStateOf(false) }

    var location by remember { mutableStateOf("") }
    var method by remember { mutableStateOf("") }
    var duration by remember { mutableStateOf("30") }
    var eventNote by remember { mutableStateOf("") }
    var autoLockInput by remember(state.autoLockMinutes) { mutableStateOf(state.autoLockMinutes.toString()) }

    val selectedLabel = state.partners.firstOrNull { it.id == selectedPartnerId }?.nickname ?: "单身/自己"

    Scaffold(topBar = { TopAppBar(title = { Text("doLoveTime 本地版") }) }) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("隐私设置", fontWeight = FontWeight.Bold)
                        Text("最近任务缩略图已隐藏（防截图已开启）")
                        OutlinedTextField(
                            value = autoLockInput,
                            onValueChange = { autoLockInput = it },
                            label = { Text("自动锁定时间(分钟，0=切后台立刻锁)") }
                        )
                        Button(onClick = {
                            onSetAutoLockMinutes(autoLockInput.toLongOrNull() ?: 1L)
                            autoLockInput = state.autoLockMinutes.toString()
                        }) { Text("保存自动锁定") }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("对象管理", fontWeight = FontWeight.Bold)
                        OutlinedTextField(value = partnerName, onValueChange = { partnerName = it }, label = { Text("对象昵称") })
                        OutlinedTextField(value = partnerNote, onValueChange = { partnerNote = it }, label = { Text("备注") })
                        Button(onClick = {
                            onAddPartner(partnerName, partnerNote)
                            partnerName = ""
                            partnerNote = ""
                        }) { Text("添加对象") }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("新增记录", fontWeight = FontWeight.Bold)

                        ExposedDropdownMenuBox(
                            expanded = dropdownExpanded,
                            onExpandedChange = { dropdownExpanded = !dropdownExpanded }
                        ) {
                            OutlinedTextField(
                                value = selectedLabel,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("选择对象") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            androidx.compose.material3.ExposedDropdownMenu(
                                expanded = dropdownExpanded,
                                onDismissRequest = { dropdownExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("单身/自己") },
                                    onClick = {
                                        selectedPartnerId = null
                                        dropdownExpanded = false
                                    }
                                )
                                state.partners.forEach { p ->
                                    DropdownMenuItem(
                                        text = { Text(p.nickname) },
                                        onClick = {
                                            selectedPartnerId = p.id
                                            dropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        OutlinedTextField(value = location, onValueChange = { location = it }, label = { Text("地点") })
                        OutlinedTextField(value = method, onValueChange = { method = it }, label = { Text("方式") })
                        OutlinedTextField(value = duration, onValueChange = { duration = it }, label = { Text("时长(分钟)") })
                        OutlinedTextField(value = eventNote, onValueChange = { eventNote = it }, label = { Text("过程备注") })
                        Button(onClick = {
                            onAddEvent(selectedPartnerId, location, method, duration.toLongOrNull() ?: 0, eventNote)
                            location = ""
                            method = ""
                            duration = "30"
                            eventNote = ""
                        }) { Text("保存记录") }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("统计", fontWeight = FontWeight.Bold)
                        Text("日：${state.stats.day.count} 次 / ${state.stats.day.totalMinutes} 分钟")
                        Text("周：${state.stats.week.count} 次 / ${state.stats.week.totalMinutes} 分钟")
                        Text("月：${state.stats.month.count} 次 / ${state.stats.month.totalMinutes} 分钟")
                        Text("地点排行：")
                        if (state.stats.topLocations.isEmpty()) {
                            Text("暂无数据")
                        } else {
                            state.stats.topLocations.forEachIndexed { index, item ->
                                Text("${index + 1}. ${item.location} (${item.count}次)")
                            }
                        }
                    }
                }
            }

            item {
                Text("记录列表（${state.events.size}）", fontWeight = FontWeight.Bold)
            }

            items(state.events) { e ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("对象：${e.partnerName ?: "自己"}")
                        Text("地点：${e.location} | 方式：${e.method}")
                        Text("时长：${(e.endMillis - e.startMillis) / 60000} 分钟")
                        if (e.note.isNotBlank()) Text("备注：${e.note}")
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> StateFlow<T>.collectAsStateCompat(): androidx.compose.runtime.State<T> {
    val flow = this
    val initial = flow.value
    val state = remember { mutableStateOf(initial) }
    LaunchedEffect(flow) {
        flow.collect { state.value = it }
    }
    return state
}
