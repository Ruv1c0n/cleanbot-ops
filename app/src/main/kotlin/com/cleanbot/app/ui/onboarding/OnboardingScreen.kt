@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.cleanbot.app.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private const val PAGE_COUNT = 3

/**
 * Первичный онбординг при первом запуске: три страницы-слайда с описанием приложения.
 * Адрес сервера здесь НЕ вводится — настройки живут в экране настроек (шестерёнка
 * на главном экране); онбординг лишь объясняет, где их искать.
 * Route "onboarding" в CleanBotNavHost, стартовый экран до [com.cleanbot.app.di.AppContainer.markOnboardingDone].
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
    val scope = rememberCoroutineScope()
    val isLastPage = pagerState.currentPage == PAGE_COUNT - 1

    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { page ->
                when (page) {
                    0 -> WelcomePage()
                    1 -> SimulatorPage()
                    else -> StartPage()
                }
            }
            OnboardingControls(
                currentPage = pagerState.currentPage,
                onNext = {
                    scope.launch {
                        pagerState.animateScrollToPage(pagerState.currentPage + 1)
                    }
                },
                onDone = onDone,
                isLastPage = isLastPage
            )
        }
    }
}

/** Нижняя панель: «Пропустить» слева, точки-индикаторы по центру, «Далее»/«Начать» справа. */
@Composable
private fun OnboardingControls(
    currentPage: Int,
    isLastPage: Boolean,
    onNext: () -> Unit,
    onDone: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onDone) { Text("Пропустить") }
        Spacer(Modifier.weight(1f))
        PagerDots(pageCount = PAGE_COUNT, currentPage = currentPage)
        Spacer(Modifier.weight(1f))
        Button(
            onClick = if (isLastPage) onDone else onNext
        ) {
            Text(if (isLastPage) "Начать" else "Далее")
        }
    }
}

/** Точки-индикаторы страниц: текущая подсвечена основным цветом темы. */
@Composable
private fun PagerDots(pageCount: Int, currentPage: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(pageCount) { index ->
            val active = index == currentPage
            Box(
                modifier = Modifier
                    .size(if (active) 10.dp else 8.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                    )
            )
        }
    }
}

@Composable
private fun WelcomePage() {
    OnboardingPage(
        accent = "🤖",
        title = "Добро пожаловать в cleanbot-ops",
        body = "Консоль оператора уборочных роботов. Смотрите состояние парка, " +
            "отправляйте команды, следите за уборкой на карте и за статистикой — " +
            "как за настоящим робопарком."
    )
}

@Composable
private fun SimulatorPage() {
    OnboardingPage(
        accent = "📡",
        title = "Подключитесь к симулятору",
        body = "Роботы живут в симуляторе на компьютере. Укажите его адрес: " +
            "в эмуляторе это 10.0.2.2:8080, на телефоне — IP компьютера в той же сети " +
            "(порт 8080). Адрес можно менять в любой момент — иконка шестерёнки " +
            "на главном экране."
    )
}

@Composable
private fun StartPage() {
    OnboardingPage(
        accent = "🚀",
        title = "Начните работу",
        bullets = listOf(
            "Парк — статус всех роботов",
            "Дашборд — команды и телеметрия",
            "Карта — зоны уборки и редактор планов",
            "Расписания и статистика",
            "Уведомления об авариях"
        )
    )
}

/** Шаблон страницы: акцент-эмодзи, заголовок, опциональный текст и/или список пунктов. */
@Composable
private fun OnboardingPage(
    accent: String,
    title: String,
    body: String? = null,
    bullets: List<String> = emptyList()
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(accent, style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(16.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        body?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
        }
        bullets.forEach { bullet ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
                Text(
                    bullet,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
