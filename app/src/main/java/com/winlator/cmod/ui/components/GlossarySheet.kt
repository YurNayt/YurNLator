package com.winlator.cmod.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * "What is all this?": plain-language explanations of the container / game settings, in
 * English and Russian (picked by the app language). Ported from Bannerlator's glossary and
 * rewritten for YurNLator's own settings.
 *
 * [labels] are the setting labels (as written in the editors) that show a "?" next to them
 * and open the sheet on this entry.
 */
class GlossaryEntry(
    val term: String,
    val en: String,
    val ru: String,
    val termRu: String? = null,
    val labels: List<String> = emptyList()
) {
    fun title(ru: Boolean) = if (ru) termRu ?: term else term
    fun text(ru: Boolean) = if (ru) this.ru else en
}

class GlossarySection(val en: String, val ru: String, val entries: List<GlossaryEntry>) {
    fun title(ru: Boolean) = if (ru) this.ru else en
}

private fun e(term: String, en: String, ru: String, termRu: String? = null, vararg labels: String) =
    GlossaryEntry(term, en, ru, termRu, labels.toList())

object Glossary {
    val sections: List<GlossarySection> = listOf(
        GlossarySection("Graphics translation", "Перевод графики", listOf(
            e("DirectX", "Microsoft's graphics API that almost every Windows game uses. Phones don't speak it, so it is translated to Vulkan (or OpenGL) by the layers below.",
                "Графический API Microsoft, на котором работают почти все игры для Windows. Телефоны его не понимают, поэтому слои ниже переводят его в Vulkan (или OpenGL)."),
            e("DX Wrapper", "Picks which translator turns the game's DirectX into something the phone draws: DXVK (+ VKD3D for DirectX 12) is the fast default, WineD3D is the slow but very compatible fallback for old games.",
                "Выбор переводчика DirectX: DXVK (+ VKD3D для DirectX 12) — быстрый вариант по умолчанию, WineD3D — медленнее, но очень совместимый, выручает в старых играх.",
                "Обёртка DirectX", "DX Wrapper"),
            e("DXVK", "Translates DirectX 8/9/10/11 to Vulkan. The fastest way to run most games. Newer versions fix more games but need a newer driver; the 'async' builds compile shaders in the background to cut stutter.",
                "Переводит DirectX 8/9/10/11 в Vulkan — самый быстрый путь для большинства игр. Новые версии чинят больше игр, но требуют свежий драйвер; сборки async компилируют шейдеры в фоне, меньше фризов.",
                null, "DXVK Version"),
            e("VKD3D", "VKD3D-Proton translates DirectX 12 to Vulkan. Needed only for DX12 games; heavier than DXVK and the most demanding on the driver.",
                "VKD3D-Proton переводит DirectX 12 в Vulkan. Нужен только играм на DX12; тяжелее DXVK и сильнее всего нагружает драйвер.",
                null, "VKD3D Version"),
            e("VKD3D Feature Level", "Which DirectX 12 level VKD3D reports to the game. Some games refuse to start below 12_1 or 12_2; lower it only if a game crashes on start.",
                "Какой уровень DirectX 12 VKD3D сообщает игре. Некоторые игры не запускаются ниже 12_1 или 12_2; понижайте, только если игра падает на старте.",
                "Уровень VKD3D", "VKD3D Feature Level"),
            e("Async / Async Cache", "DXVK async compiles new shaders in the background and skips drawing the object until it is ready: much less stutter, sometimes a brief missing effect. Async Cache saves them so the next run is smooth from the start.",
                "DXVK async компилирует новые шейдеры в фоне и пропускает объект, пока шейдер не готов: гораздо меньше фризов, иногда на миг пропадает эффект. Async Cache сохраняет их, и следующий запуск плавный сразу.",
                null, "Async", "Async Cache"),
            e("Frame Rate (DXVK)", "DXVK's own frame cap (DXVK_FRAME_RATE). 0 means no cap. Works only for DXVK games; for anything else use the FPS limiter in the in-game menu.",
                "Ограничитель кадров самого DXVK (DXVK_FRAME_RATE). 0 — без ограничения. Работает только в играх через DXVK; для остальных — ограничитель FPS в игровом меню.",
                "Частота кадров (DXVK)", "Frame Rate"),
            e("Max Frame Latency", "Limits how many frames the CPU may prepare ahead of the GPU. On: less input lag; off: sometimes a little more FPS.",
                "Ограничивает, сколько кадров процессор готовит наперёд. Включено — меньше задержка ввода; выключено — иногда чуть больше FPS.",
                "Задержка кадров", "Max Frame Latency"),
            e("Mali barrier tweaks", "Mali only: relaxes DXVK's GPU barriers (relaxedBarriers, ignoreGraphicsBarriers, allowMapFlagNoWait). Often a nice FPS gain on Mali; turn it off if a game shows flickering or broken shadows.",
                "Только Mali: ослабляет барьеры GPU в DXVK (relaxedBarriers, ignoreGraphicsBarriers, allowMapFlagNoWait). Часто заметно поднимает FPS на Mali; выключите, если в игре мерцание или битые тени.",
                "Барьеры Mali", "Mali barrier tweaks"),
            e("DXVK HUD", "DXVK's own on-screen readout (FPS, frame time, GPU, driver). Only for diagnosing; it does not change the game.",
                "Собственный экранный счётчик DXVK (FPS, время кадра, GPU, драйвер). Только для диагностики, на игру не влияет.",
                null, "DXVK HUD"),
            e("WineD3D", "Wine's built-in DirectX translator. Slower than DXVK but handles very old and unusual games (DirectX 1–7, odd 2D engines).",
                "Встроенный переводчик DirectX в Wine. Медленнее DXVK, но справляется с очень старыми и необычными играми (DirectX 1–7, странные 2D-движки)."),
            e("CSMT", "WineD3D's command-stream threading: drawing runs on its own thread. Usually faster; turn off if an old game flickers or hangs.",
                "Многопоточность WineD3D: отрисовка идёт в отдельном потоке. Обычно быстрее; выключите, если старая игра мерцает или зависает.",
                null, "CSMT"),
            e("Strict Shader Math", "WineD3D follows Direct3D's exact shader math rules. Fixes rare visual glitches, costs a little speed.",
                "WineD3D точно соблюдает математику шейдеров Direct3D. Исправляет редкие графические глюки, немного снижает скорость.",
                "Строгая математика шейдеров", "Strict Shader Math"),
            e("Offscreen Rendering Mode", "How WineD3D draws to hidden surfaces: fbo (default, fast) or backbuffer (for old games that show black screens with fbo).",
                "Как WineD3D рисует в скрытые поверхности: fbo (по умолчанию, быстро) или backbuffer (для старых игр, у которых с fbo чёрный экран).",
                null, "Offscreen Rendering Mode"),
            e("Wine Renderer", "What WineD3D draws with: vulkan or gl (OpenGL through Zink). Try the other one if a WineD3D game shows nothing.",
                "Через что рисует WineD3D: vulkan или gl (OpenGL через Zink). Если игра на WineD3D ничего не показывает — попробуйте другой.",
                "Рендер Wine", "Wine Renderer"),
            e("Video Memory", "How much video memory WineD3D tells the game it has (MB). Raise it if a game complains about too little VRAM.",
                "Сколько видеопамяти WineD3D сообщает игре (МБ). Увеличьте, если игра жалуется на нехватку VRAM.",
                "Видеопамять", "Video Memory"),
            e("DDraw Wrapper", "For DirectDraw (2D, DirectX 1–7) games: wined3d is Wine's own; cnc-ddraw is great for old 2D games (C&C, Diablo, Age of Empires); dd7to9 and D7VK move Direct3D 7 to DirectX 9 / Vulkan for speed.",
                "Для игр на DirectDraw (2D, DirectX 1–7): wined3d — штатный в Wine; cnc-ddraw отлично подходит старым 2D-играм (C&C, Diablo, Age of Empires); dd7to9 и D7VK переводят Direct3D 7 в DirectX 9 / Vulkan ради скорости.",
                "Обёртка DirectDraw", "DDraw Wrapper"),
            e("OpenGL / Zink", "OpenGL games are drawn through Zink (OpenGL on top of Vulkan) or Freedreno (Adreno's native OpenGL). Zink works on every GPU.",
                "OpenGL-игры рисуются через Zink (OpenGL поверх Vulkan) или Freedreno (родной OpenGL для Adreno). Zink работает на любом GPU.",
                null, "Graphics Driver"),
            e("Shader cache", "The first time you play, the game compiles its shaders, which causes short stutters. The cache keeps them, so later sessions run smoothly.",
                "При первой игре компилируются шейдеры — отсюда короткие фризы. Кэш их сохраняет, и следующие сессии идут плавно.",
                "Кэш шейдеров"),
        )),
        GlossarySection("The Windows layer", "Слой Windows", listOf(
            e("Wine / Proton", "Wine runs Windows programs on Linux/Android without Windows. Proton is Valve's Wine with extra game fixes. Newer is not always faster: if a game misbehaves, try another version.",
                "Wine запускает программы Windows на Linux/Android без самой Windows. Proton — Wine от Valve с дополнительными исправлениями для игр. Новее не всегда быстрее: если игра капризничает, попробуйте другую версию.",
                null, "Wine / Proton"),
            e("arm64ec", "Wine builds where Windows parts run natively on ARM and only the game's x86 code is emulated. Usually noticeably faster than the classic x86-64 builds.",
                "Сборки Wine, где части Windows работают нативно на ARM, а эмулируется только x86-код самой игры. Обычно заметно быстрее классических x86-64 сборок."),
            e("Container", "A separate Windows install (its own C: drive, settings and games). Containers don't affect each other, so you can keep different setups for different games.",
                "Отдельная «установка Windows» со своим диском C:, настройками и играми. Контейнеры друг на друга не влияют — можно держать разные настройки для разных игр.",
                "Контейнер"),
            e("Sync (esync / ntsync / wineserver)", "How the game's threads wait for each other. esync is the fast default; ntsync is the fastest but needs the ntsync driver in the phone's kernel (otherwise Wine falls back to esync); wineserver is the slowest and most compatible, for games that hang. fsync is impossible on Android.",
                "Как потоки игры ждут друг друга. esync — быстрый вариант по умолчанию; ntsync — самый быстрый, но нужен драйвер ntsync в ядре телефона (иначе Wine вернётся к esync); wineserver — самый медленный и совместимый, для зависающих игр. fsync на Android невозможен.",
                "Синхронизация", "Sync"),
            e("Startup Selection", "Which Windows services start with Wine. Essential (default) is lighter and faster to start; Normal loads everything (some launchers need it); Aggressive stops services for the lightest start.",
                "Какие службы Windows стартуют вместе с Wine. Essential (по умолчанию) — легче и быстрее; Normal — всё подряд (нужно некоторым лаунчерам); Aggressive — останавливает службы ради самого лёгкого старта.",
                "Режим запуска", "Startup Selection"),
            e("Locale (LC_ALL)", "The language and region Wine reports. Pick Russian for games that show broken Cyrillic or need a Russian system locale.",
                "Язык и регион, которые видит Wine. Выберите русский, если в игре «кракозябры» вместо кириллицы или ей нужна русская локаль.",
                "Локаль", "Locale (LC_ALL)"),
            e("Environment variables", "Advanced switches (like DXVK_HUD=fps or MESA_VK_WSI_PRESENT_MODE=mailbox) for fine-tuning. Most people never need them; a game's own variables override the container's.",
                "Расширенные переключатели (например DXVK_HUD=fps или MESA_VK_WSI_PRESENT_MODE=mailbox) для тонкой настройки. Обычно не нужны; переменные игры перекрывают переменные контейнера.",
                "Переменные окружения", "Environment"),
            e("DLL overrides", "Tell Wine to use a game's own copy of a system DLL (native) or Wine's version (builtin). A common fix for games that ship their own DLLs.",
                "Указывают Wine, брать ли собственную DLL игры (native) или встроенную (builtin). Частое решение для игр со своими DLL."),
            e("Components", "Extra Windows runtimes some games need (Visual C++, .NET, DirectX, XNA…). Install the missing one when a game complains about it.",
                "Дополнительные библиотеки Windows, нужные некоторым играм (Visual C++, .NET, DirectX, XNA…). Ставьте ту, на отсутствие которой жалуется игра.",
                "Компоненты"),
            e("Fullscreen Stretched", "Stretches the picture to the whole phone screen, ignoring the aspect ratio. Off keeps black bars but the correct proportions.",
                "Растягивает картинку на весь экран без учёта пропорций. Выключено — чёрные полосы, но правильные пропорции.",
                "Растянуть на весь экран", "Fullscreen Stretched"),
            e("Mouse Warp Override", "How Wine handles games that move the mouse pointer themselves (camera control in 3D games). Try enable or force if the camera spins or doesn't turn.",
                "Как Wine обрабатывает игры, которые сами двигают указатель мыши (обзор в 3D-играх). Попробуйте enable или force, если камера крутится или не поворачивается.",
                null, "Mouse Warp Override"),
        )),
        GlossarySection("Running x86 on ARM", "Запуск x86 на ARM", listOf(
            e("Box64 / WOWBox64", "Translates x86-64 (and through WOW64, 32-bit) PC code to the phone's ARM CPU. WOWBox64 is the build used inside arm64ec Wine.",
                "Переводит x86-64-код ПК (а через WOW64 и 32-битный) для ARM-процессора телефона. WOWBox64 — версия для Wine arm64ec.",
                null, "Box64 Version", "WOWBox64 Version"),
            e("FEXCore", "Another x86 → ARM translator, often more accurate than Box64. In arm64ec Wine it runs the 32-bit and x86-64 parts of the game.",
                "Ещё один переводчик x86 → ARM, часто точнее Box64. В Wine arm64ec исполняет 32-битные и x86-64 части игры.",
                null, "FEXCore Version"),
            e("32-bit Emulator", "Which translator runs 32-bit games in arm64ec Wine: FEXCore (usually more compatible) or WOWBox64 (sometimes faster).",
                "Чем запускать 32-битные игры в Wine arm64ec: FEXCore (обычно совместимее) или WOWBox64 (иногда быстрее).",
                "32-битный эмулятор", "32-bit Emulator"),
            e("Translator presets", "Ready-made sets of Box64 / FEXCore options. Stability/Compatibility are the most accurate; Performance and Extreme relax memory ordering (TSO) and other checks for more FPS but can break some games. Start with the default; you can make your own Custom preset.",
                "Готовые наборы опций Box64 / FEXCore. Stability/Compatibility — самые точные; Performance и Extreme ослабляют порядок памяти (TSO) и другие проверки ради FPS, но могут ломать некоторые игры. Начинайте с пресета по умолчанию; можно сделать свой Custom.",
                "Пресеты эмулятора", "FEXCore Preset", "Box64 Preset", "WOWBox64 Preset"),
            e("Force SSBS", "arm64ec Wine clears the CPU's SSBS bit when it handles signals, so game threads run with a slow memory-safety mode on. This sets it back: often a noticeable CPU speed-up. Turn off only if a game crashes.",
                "Wine arm64ec сбрасывает бит процессора SSBS при обработке сигналов, и потоки игры работают в медленном защитном режиме памяти. Эта настройка возвращает бит — часто заметный прирост скорости CPU. Выключайте, только если игра вылетает.",
                null, "Force SSBS (CPU speed fix, arm64ec)"),
            e("Sync CPU Topology", "Tells the game the phone's real core layout (big / little cores), so its threads land on the fast cores.",
                "Сообщает игре реальную раскладку ядер телефона (большие / малые), чтобы её потоки попадали на быстрые ядра.",
                "Топология процессора", "Sync CPU Topology"),
            e("Processor Affinity", "Which CPU cores the game may use. Leaving only the big cores can help games that stutter when a thread lands on a slow core.",
                "На каких ядрах разрешено работать игре. Если оставить только большие ядра, может уйти фриз от потоков на медленных ядрах.",
                "Привязка к ядрам", "Processor Affinity", "Processor Affinity (32-bit apps)"),
        )),
        GlossarySection("GPU & driver", "Видеочип и драйвер", listOf(
            e("Vulkan", "The modern low-level graphics API the phone's GPU speaks. Every translator above ends up in Vulkan.",
                "Современный низкоуровневый графический API, который понимает видеочип телефона. Все переводчики выше в итоге выводят в Vulkan."),
            e("Adreno vs Mali", "The two main phone GPU families. Adreno (Snapdragon) has the best support (Turnip). Mali (MediaTek, Exynos, Tensor) works through a wrapper and needs BCn emulation for PC textures.",
                "Две основные семьи мобильных GPU. Adreno (Snapdragon) поддерживается лучше всего (Turnip). Mali (MediaTek, Exynos, Tensor) работает через обёртку и требует эмуляцию BCn для текстур ПК."),
            e("Driver (Turnip)", "The Vulkan driver the game uses. Turnip is the open-source Adreno driver, usually faster and more compatible than the phone's own; 'System' is the phone's built-in driver (the only option on Mali, apart from PanVK).",
                "Vulkan-драйвер для игры. Turnip — открытый драйвер для Adreno, обычно быстрее и совместимее родного; «System» — встроенный драйвер телефона (на Mali единственный вариант, кроме PanVK).",
                "Драйвер (Turnip)", "Driver Version"),
            e("Vulkan Wrapper", "A layer between the game and the phone's own driver that adds missing features and fixes. On Mali the mali-wrapper adds what DXVK needs; on Adreno the built-in one is used.",
                "Слой между игрой и родным драйвером телефона, который добавляет недостающие функции и исправления. На Mali — mali-wrapper с тем, что нужно DXVK; на Adreno — встроенный.",
                "Vulkan-обёртка", "Vulkan Wrapper"),
            e("Vulkan Version", "The Vulkan level reported to DXVK/VKD3D. Higher can unlock features games ask for, but it is capped by what the driver really supports.",
                "Версия Vulkan, которую видят DXVK/VKD3D. Выше — больше функций для игр, но всё равно не выше того, что реально умеет драйвер.",
                "Версия Vulkan", "Vulkan Version"),
            e("GPU Name", "Reports a different graphics card to games (GPU spoofing). Some games run or unlock better settings only for known GPUs. 'Device' reports the real one.",
                "Подменяет название видеокарты для игр. Некоторые игры запускаются или открывают настройки только на известных GPU. «Device» — показать настоящий.",
                "Имя GPU", "GPU Name"),
            e("Max Device Memory", "Caps how much video memory the driver reports. A fix for out-of-memory crashes or heavy stutter; 0 = no cap.",
                "Ограничивает объём видеопамяти, который сообщает драйвер. Помогает при вылетах из-за памяти или сильных фризах; 0 — без ограничения.",
                "Лимит видеопамяти", "Max Device Memory"),
            e("Driver Present Mode", "The present mode the wrapper forces on the game: mailbox (smooth, low lag), fifo (vsync, saves battery), immediate (lowest lag, may tear), relaxed (vsync that doesn't wait on late frames).",
                "Режим вывода, который обёртка навязывает игре: mailbox (плавно, малая задержка), fifo (vsync, экономнее), immediate (минимальная задержка, возможны разрывы), relaxed (vsync, не ждущий опоздавшие кадры).",
                "Режим вывода драйвера", "Driver Present Mode"),
            e("Sync Frame", "Makes the GPU finish each frame before the next starts. Fixes flicker and broken frames; costs some FPS.",
                "GPU полностью дорисовывает каждый кадр перед следующим. Убирает мерцание и битые кадры, но отнимает часть FPS.",
                "Синхронизация кадра", "Sync Frame"),
            e("Disable Present Wait", "Hides the VK_KHR_present_wait feature, which some drivers implement badly (stalls or hangs). A compatibility switch.",
                "Скрывает функцию VK_KHR_present_wait, которую некоторые драйверы реализуют плохо (подвисания). Переключатель совместимости.",
                null, "Disable Present Wait"),
            e("Resource Type", "Which kind of memory the wrapper uses to hand frames to the screen: auto, dmabuf, ahb (Android hardware buffer) or opaque. Change only if the picture is black or the game won't start.",
                "Какую память обёртка использует для передачи кадров на экран: auto, dmabuf, ahb (Android hardware buffer) или opaque. Меняйте, только если чёрный экран или игра не стартует.",
                "Тип ресурсов", "Resource Type"),
            e("BCn emulation", "PC games store textures in BCn (DXT/S3TC) format, which Adreno reads natively but Mali can't. BCn emulation decodes them on the fly; Type picks compute (GPU) or software decoding; the cache keeps decoded textures to cut stutter. No effect on Adreno.",
                "Игры ПК хранят текстуры в формате BCn (DXT/S3TC): Adreno читает его сам, Mali — нет. Эмуляция BCn раскодирует их на лету; Type — на GPU (compute) или процессоре (software); кэш хранит готовые текстуры, меньше фризов. На Adreno не влияет.",
                "Эмуляция BCn", "BCN Emulation", "BCN Emulation Type", "BCN Emulation Cache"),
            e("BCn layer (Winlator Mali)", "The Mali BCn layer: 'current' is the new faster one, 'legacy' the old one for wrappers where current won't start. It can transcode textures to ASTC / ETC2 (formats Mali reads in hardware); quality trades sharpness for speed.",
                "Слой BCn для Mali: «current» — новый и быстрее, «legacy» — старый, для обёрток, где current не запускается. Может перекодировать текстуры в ASTC / ETC2 (их Mali читает аппаратно); качество — баланс чёткости и скорости.",
                "Слой BCn (Mali)", "BCn layer (Winlator Mali)", "BCn transcode to", "BCn quality"),
            e("Rendering Mode (GMEM / sysmem)", "Turnip only. GMEM draws the frame in small tiles in fast on-chip memory (efficient, mobile style); Sysmem draws straight to main memory (most compatible); Autotuner lets the driver choose per pass.",
                "Только Turnip. GMEM рисует кадр плитками в быстрой памяти на чипе (экономно, по-мобильному); Sysmem — сразу в общую память (самое совместимое); Autotuner — драйвер выбирает сам.",
                "Режим рендеринга", "Rendering Mode"),
            e("Disabled Vulkan Extensions", "Hides chosen Vulkan extensions from the game. A last-resort fix when a broken extension in the driver makes a game crash.",
                "Скрывает от игры выбранные расширения Vulkan. Крайняя мера, когда сломанное расширение драйвера роняет игру.",
                "Отключённые расширения", "Disabled Vulkan Extensions", "Blacklisted Extensions"),
        )),
        GlossarySection("Picture", "Картинка", listOf(
            e("Screen Size", "The resolution Windows and the game see. Lower = more FPS; the picture is scaled to the phone screen anyway.",
                "Разрешение, которое видят Windows и игра. Ниже — больше FPS; на экран телефона картинка всё равно масштабируется.",
                "Разрешение", "Screen Size", "Custom resolution"),
            e("Display backend (X11 / Wayland)", "How Wine's windows reach the screen. X11 is the proven default. Wayland is experimental: needs a Proton layer with the Wayland driver and a Mesa driver (Turnip / PanVK); otherwise the game starts on X11.",
                "Как окна Wine попадают на экран. X11 — проверенный вариант по умолчанию. Wayland — экспериментальный: нужен Proton со слоем Wayland и Mesa-драйвер (Turnip / PanVK), иначе игра запустится на X11.",
                "Дисплей (X11 / Wayland)", "Display backend"),
            e("Renderer (Vulkan / EGL)", "What draws the finished picture on the phone screen. Vulkan is faster and supports upscaling filters and frame generation; EGL (OpenGL ES) is the compatible fallback.",
                "Чем готовая картинка выводится на экран телефона. Vulkan быстрее и поддерживает фильтры масштабирования и генерацию кадров; EGL (OpenGL ES) — запасной совместимый вариант.",
                "Рендерер", "Renderer"),
            e("Color order (RGBA / BGRA)", "The order of the red and blue channels. Switch only if a game's colours look swapped (blue faces, orange sky).",
                "Порядок красного и синего каналов. Меняйте, только если цвета перепутаны (синие лица, оранжевое небо).",
                "Порядок цветов", "Color order (BGRA = swap red / blue)", "Swap red/blue channels"),
            e("Texture Filter", "How the game's picture is scaled to the screen: Bilinear (smooth), Nearest (sharp pixels, for pixel art), Snapdragon Super Resolution and AMD FSR (sharper upscaling from a low resolution), Lanczos (sharp, a bit heavier).",
                "Как картинка игры масштабируется на экран: Bilinear (мягко), Nearest (чёткие пиксели, для пиксель-арта), Snapdragon Super Resolution и AMD FSR (чёткое масштабирование с низкого разрешения), Lanczos (резко, чуть тяжелее).",
                "Фильтр текстур", "Texture Filter"),
            e("Present Mode (FIFO / Mailbox)", "How our renderer hands frames to the screen. FIFO waits for each refresh: smooth, tear-free, saves battery. Mailbox always shows the newest frame: lower lag, the right choice for frame generation.",
                "Как наш рендерер отдаёт кадры экрану. FIFO ждёт каждое обновление экрана: плавно, без разрывов, экономно. Mailbox всегда показывает самый свежий кадр: меньше задержка, лучший выбор для генерации кадров.",
                "Режим вывода", "Present Mode"),
            e("Renderer Driver", "Which Vulkan driver our own screen renderer uses (separate from the game's driver). Change it only if the screen stays black.",
                "Какой Vulkan-драйвер использует наш экранный рендерер (отдельно от драйвера игры). Меняйте, только если экран остаётся чёрным.",
                "Драйвер рендерера", "Renderer Driver"),
            e("DisplayX (Bypass X11)", "Hands the game's frames straight to the screen, skipping the X11 copy: lower latency and less work. Performance mode and 'present at refresh rate' tune its pacing.",
                "Передаёт кадры игры прямо на экран, минуя копирование через X11: меньше задержка и нагрузка. Performance mode и «present at refresh rate» настраивают темп вывода.",
                null, "Bypass X11", "Performance mode", "Present at refresh rate"),
            e("Sharpness Effect (CAS / DLS)", "A sharpening pass on top of the picture: CAS (AMD contrast-adaptive) or DLS (denoised luma sharpening). Useful with a lowered resolution.",
                "Повышение резкости поверх картинки: CAS (адаптивный от AMD) или DLS (резкость по яркости с шумоподавлением). Полезно при пониженном разрешении.",
                "Резкость", "Sharpness Effect"),
            e("Frame generation", "Inserts extra in-between frames for smoother motion. Adds about a frame of input lag and can show small artifacts. Win-FG and WFG are built in; LSFG uses your own Lossless.dll. Turn it on in the in-game menu; needs the Vulkan renderer.",
                "Вставляет промежуточные кадры для плавности. Добавляет примерно кадр задержки и может давать мелкие артефакты. Win-FG и WFG встроены; LSFG использует ваш Lossless.dll. Включается в игровом меню, нужен рендерер Vulkan.",
                "Генерация кадров"),
            e("FPS limiter", "Caps frames per second for any game. Less heat and battery drain, and a steadier frame rate. Set it live in the in-game menu.",
                "Ограничивает FPS в любой игре. Меньше нагрев и расход батареи, ровнее частота кадров. Настраивается прямо в игровом меню.",
                "Ограничитель FPS"),
            e("Refresh rate", "The screen refresh rate games see (60, 90, 120 Hz…). Wine 9 and older only switch the virtual desktop at 60 Hz, so they report 60 Hz; Wine 10+ use the phone's real rates.",
                "Частота обновления экрана, которую видит игра (60, 90, 120 Гц…). Wine 9 и более ранние умеют только 60 Гц на виртуальном рабочем столе, поэтому сообщает 60 Гц; Wine 10+ — реальные частоты телефона.",
                "Частота обновления"),
        )),
        GlossarySection("HUD & performance", "HUD и производительность", listOf(
            e("Performance HUD", "The on-screen stats overlay: YurNLator (compact, with CPU/GPU load, clocks, temperature, power), Bannerlator styles, or MangoHud (inside the game, via Vulkan). Can be switched in the in-game menu.",
                "Экранная статистика: YurNLator (компактный, с нагрузкой и частотами CPU/GPU, температурой, потреблением), стили Bannerlator или MangoHud (внутри игры, через Vulkan). Переключается в игровом меню.",
                "HUD производительности", "Performance HUD"),
            e("Keep MangoHud ready", "Loads MangoHud hidden with the game, so the in-game menu can show it any time. Costs a little performance even while hidden.",
                "Загружает MangoHud скрыто вместе с игрой, чтобы его можно было в любой момент показать из игрового меню. Немного отнимает производительность даже скрытым.",
                "Держать MangoHud наготове", "Keep MangoHud ready"),
            e("Sustained performance mode", "Asks Android for steady clocks instead of short boosts: a bit lower peak, much less throttling over a long session.",
                "Просит Android держать ровные частоты вместо коротких рывков: пик чуть ниже, но гораздо меньше троттлинга в долгой игре.",
                "Режим стабильной производительности"),
            e("Lock GPU to max clock", "Keeps the GPU at its top frequency: on Adreno even without root (KGSL turbo), on MediaTek and others with root. The kernel can still throttle when hot. More FPS, more heat: best with a cooler.",
                "Держит GPU на максимальной частоте: на Adreno даже без root (KGSL turbo), на MediaTek и других — с root. При перегреве ядро всё равно может снижать частоту. Больше FPS и больше нагрев — лучше с кулером.",
                "Фиксация частоты GPU"),
            e("Benchmark", "YurnBench runs short tests of settings combinations to find the fastest working one for your phone; game mode measures a real game's own benchmark (Tomb Raider, GTA V, Source timedemo…) and can apply the winner to that game.",
                "YurnBench прогоняет короткие тесты сочетаний настроек и находит самое быстрое рабочее для вашего телефона; режим игры замеряет встроенный бенчмарк настоящей игры (Tomb Raider, GTA V, Source timedemo…) и может применить победителя к этой игре.",
                "Бенчмарк"),
        )),
        GlossarySection("Controls & audio", "Управление и звук", listOf(
            e("Exclusive Input", "Gives the controller to the game alone, so Android and the game don't both react to it. Then XInput / DInput pick which kind of controller the game sees.",
                "Отдаёт геймпад только игре, чтобы Android и игра не реагировали на него оба. Затем XInput / DInput выбирают, каким геймпадом его увидит игра.",
                "Эксклюзивный ввод", "Exclusive Input"),
            e("XInput vs DInput", "The two ways Windows games read controllers. XInput is the modern Xbox-style one; DInput is the older one. Match what the game expects; disable XInput if a game sees the pad twice.",
                "Два способа, которыми игры Windows читают геймпад. XInput — современный, как у Xbox; DInput — старый. Ставьте то, что ждёт игра; отключите XInput, если игра видит геймпад дважды.",
                null, "Enable XInput", "Enable DInput", "Disable XInput"),
            e("Controls Profile", "The on-screen controls layout (buttons, sticks, touch areas) used with this game. Make and edit layouts in Input Controls.",
                "Раскладка экранного управления (кнопки, стики, сенсорные зоны) для этой игры. Создаются и редактируются в разделе управления.",
                "Профиль управления", "Controls Profile"),
            e("Mouse options", "Relative Mouse sends movement instead of a position (for 3D games that capture the cursor); Disable Mouse ignores touch-as-mouse; Simulated Touchscreen makes a tap click exactly under the finger.",
                "Relative Mouse передаёт смещение вместо позиции (для 3D-игр, захватывающих курсор); Disable Mouse отключает мышь от касаний; Simulated Touchscreen — касание кликает ровно под пальцем.",
                "Настройки мыши", "Relative Mouse", "Disable Mouse", "Simulated Touchscreen"),
            e("Gyro aiming", "Aim by tilting the phone. Set the target (right stick, left stick or mouse), the activator button and the sensitivity in the controls settings.",
                "Прицеливание наклоном телефона. Цель (правый стик, левый стик или мышь), кнопка-активатор и чувствительность задаются в настройках управления.",
                "Гироскоп"),
            e("Audio Driver", "How the game's sound reaches Android. ALSA is simple and light (default); PulseAudio is a full sound server some games need; Oboe talks to Android audio directly (choose latency and backend). Switch if there's no sound or it crackles.",
                "Как звук игры попадает в Android. ALSA — простой и лёгкий (по умолчанию); PulseAudio — полноценный звуковой сервер, нужный некоторым играм; Oboe работает с аудио Android напрямую (задержка и бэкенд настраиваются). Меняйте, если нет звука или он трещит.",
                "Аудиодрайвер", "Audio Driver", "Oboe latency", "Oboe backend"),
            e("MIDI SoundFont", "A sound bank for MIDI music in older games. Pick one if a game's music is silent.",
                "Банк звуков для MIDI-музыки в старых играх. Выберите, если в игре нет музыки.",
                null, "MIDI SoundFont"),
        )),
    )

    private val byLabel: Map<String, GlossaryEntry> by lazy {
        buildMap { sections.forEach { s -> s.entries.forEach { en -> en.labels.forEach { put(it, en) } } } }
    }

    /** The entry explaining the setting shown with this label, or null. */
    fun forLabel(label: String): GlossaryEntry? = byLabel[label]
}

@Composable
fun glossaryRussian(): Boolean {
    val locales = LocalConfiguration.current.locales
    return locales.size() > 0 && locales[0].language == "ru"
}

/**
 * A small "?" next to a setting: opens the glossary on the entry for [label]. Draws nothing
 * when the glossary has no entry for it.
 */
@Composable
fun GlossaryHelpIcon(label: String) {
    val entry = remember(label) { Glossary.forLabel(label) } ?: return
    var open by remember { mutableStateOf(false) }
    Box(
        Modifier.size(32.dp).clip(CircleShape).clickable { open = true },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Outlined.HelpOutline,
            contentDescription = entry.term,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .75f)
        )
    }
    if (open) GlossarySheet(onDismiss = { open = false }, focus = entry)
}

/** Top-bar button that opens the whole glossary. */
@Composable
fun GlossaryButton() {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Outlined.MenuBook, contentDescription = if (glossaryRussian()) "Справочник" else "What is all this?")
    }
    if (open) GlossarySheet(onDismiss = { open = false })
}

/**
 * The glossary sheet. With [focus] it opens on that entry (shown first, highlighted), with the
 * rest of the glossary below; the search box filters terms and texts in the current language.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlossarySheet(onDismiss: () -> Unit, focus: GlossaryEntry? = null) {
    val ru = glossaryRussian()
    val cs = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    val sections = remember(q, ru) {
        if (q.isEmpty()) Glossary.sections
        else Glossary.sections.mapNotNull { section ->
            val hits = section.entries.filter {
                it.term.contains(q, true) || it.title(ru).contains(q, true) || it.text(ru).contains(q, true) ||
                    it.labels.any { l -> l.contains(q, true) }
            }.sortedByDescending { it.term.contains(q, true) || it.title(ru).contains(q, true) }
            if (hits.isEmpty()) null else GlossarySection(section.en, section.ru, hits)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        KeepDialogFullscreen()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (ru) "Справочник по настройкам" else "What is all this?",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (ru) "Простыми словами о том, что делает каждая настройка."
                        else "Plain-language explanations of the settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, null) }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(if (ru) "Поиск" else "Search terms") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp)
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (focus != null && q.isEmpty()) {
                item(key = "focus") { GlossaryCard(focus, ru, highlighted = true) }
                item(key = "focus-gap") { Spacer(Modifier.padding(top = 4.dp)) }
            }
            sections.forEach { section ->
                item(key = "sec:" + section.en) {
                    Text(
                        section.title(ru).uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp, start = 4.dp)
                    )
                }
                items(section.entries, key = { section.en + ":" + it.term }) { entry ->
                    GlossaryCard(entry, ru, highlighted = false)
                }
            }
            if (sections.isEmpty()) {
                item(key = "empty") {
                    Text(
                        if (ru) "Ничего не найдено по «$q»." else "No terms match \"$q\".",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun GlossaryCard(entry: GlossaryEntry, ru: Boolean, highlighted: Boolean) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = if (highlighted) cs.primaryContainer.copy(alpha = .55f) else cs.surfaceVariant.copy(alpha = .4f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(12.dp)) {
            if (highlighted) {
                Icon(Icons.Outlined.HelpOutline, null, tint = cs.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
            }
            Column {
                Text(entry.title(ru), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (ru && entry.termRu != null && entry.termRu != entry.term) {
                    Text(entry.term, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
                Text(
                    entry.text(ru),
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
    }
}
