package com.lululu.zcoderemote.web

import com.lululu.zcoderemote.web.LoadAttemptTracker.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

/** 主框架导航的跟踪：WebView 的回调按哪一种顺序来，结论都得对（顺序是模拟器上实测的，见 LoadAttemptTracker）。 */
class LoadAttemptTrackerTest {

    private val link = "https://zcode.z.ai/remote/v4?app_version=3.16.0&sid=s1"
    private val fallback = "https://zcode.z.ai/remote/v4?app_version=4.0.0"

    @Test
    fun normalLoad_startsThenFinishesSuccessfully() {
        val tracker = LoadAttemptTracker()
        tracker.onPageStarted(link)
        assertEquals(Outcome.SUCCEEDED, tracker.onPageFinished(link))
    }

    @Test
    fun networkError_isReportedBeforeFinishAndWithoutOnPageStarted() {
        val tracker = LoadAttemptTracker()
        tracker.onNetworkError(link)
        assertEquals(Outcome.FAILED, tracker.onPageFinished(link))
    }

    @Test
    fun httpErrorArrivesBeforeOnPageStarted_andTheFailureMustSurviveIt() {
        // 兜底入口自己 404：错误先报，随后才是 onPageStarted。要是不兜住这个顺序，失败会被「开始」冲掉，
        // onPageFinished 就被当成加载成功，断网页一闪就被撤掉，屏幕上剩下服务器的 Not Found。
        val tracker = LoadAttemptTracker()
        tracker.onHttpError(fallback, fatal = true)
        tracker.onPageStarted(fallback)
        assertEquals(Outcome.FAILED, tracker.onPageFinished(fallback))
    }

    @Test
    fun httpErrorAfterOnPageStarted_isAlsoRecognised() {
        // 别的 WebView 版本可能是「先开始、后报错」：也得认。
        val tracker = LoadAttemptTracker()
        tracker.onPageStarted(fallback)
        tracker.onHttpError(fallback, fatal = true)
        assertEquals(Outcome.FAILED, tracker.onPageFinished(fallback))
    }

    @Test
    fun networkErrorAfterOnPageStarted_isAlsoRecognised() {
        val tracker = LoadAttemptTracker()
        tracker.onPageStarted(link)
        tracker.onNetworkError(link)
        assertEquals(Outcome.FAILED, tracker.onPageFinished(link))
    }

    @Test
    fun httpErrorThatThePageKeepsShowingCountsAsLoaded() {
        // 不是 404，或者是别的文档 404：服务器的错误正文照常显示，这一页算加载完成了（要记 pageLoaded、撤断网页）。
        val tracker = LoadAttemptTracker()
        tracker.onPageStarted(link)
        assertEquals(Outcome.SUCCEEDED, tracker.onPageFinished(link))

        val auth = "https://auth.example.test/login?next=x"
        tracker.onHttpError(auth, fatal = false)
        tracker.onPageStarted(auth)
        assertEquals(Outcome.SUCCEEDED, tracker.onPageFinished(auth))
    }

    @Test
    fun linkNotFound_theOldDocumentsLateCallbacksDoNotSpoilTheFallback() {
        // 电脑链接 404：我们立刻加载兜底入口，404 那份文档迟到的 onPageStarted / onPageFinished 不能把兜底这次搅了。
        val tracker = LoadAttemptTracker()
        tracker.onHttpError(link, fatal = true)          // 先报错
        tracker.onAppNavigation(fallback)                // 我们加载兜底入口（地址不同：不动上一次）
        tracker.onPageStarted(link)                      // 404 那份文档迟到的「开始」
        assertEquals(Outcome.FAILED, tracker.onPageFinished(link))   // 它的「结束」不算加载完成
        tracker.onPageStarted(fallback)
        assertEquals(Outcome.SUCCEEDED, tracker.onPageFinished(fallback))
    }

    @Test
    fun linkNotFound_withoutAnyLateCallbackFromTheOldDocument() {
        // 404 那份文档被新的导航顶掉，一个回调都不再来：兜底这次照样是一次干净的导航。
        val tracker = LoadAttemptTracker()
        tracker.onHttpError(link, fatal = true)
        tracker.onAppNavigation(fallback)
        tracker.onPageStarted(fallback)
        assertEquals(Outcome.SUCCEEDED, tracker.onPageFinished(fallback))
    }

    @Test
    fun retryAfterFailure_succeedsEvenIfTheFailedOneWasNeverFinished() {
        // 网络错误之后按「重试」加载同一条地址。就算上一次没收尾，这次也得算重新来，否则重试永远被当成失败。
        val tracker = LoadAttemptTracker()
        tracker.onNetworkError(link)
        tracker.onAppNavigation(link)
        tracker.onPageStarted(link)
        assertEquals(Outcome.SUCCEEDED, tracker.onPageFinished(link))
    }

    @Test
    fun retryAfterAFinishedFailure_succeeds() {
        // 常规情况：失败那次已经收尾了，重试是全新的一次。
        val tracker = LoadAttemptTracker()
        tracker.onNetworkError(link)
        assertEquals(Outcome.FAILED, tracker.onPageFinished(link))
        tracker.onAppNavigation(link)
        tracker.onPageStarted(link)
        assertEquals(Outcome.SUCCEEDED, tracker.onPageFinished(link))
    }

    @Test
    fun pageReloadOfTheSameFailedAddressWithoutAnAppSignalIsNotTreatedAsARetry() {
        // 同一条失败的地址又来了 onPageStarted，而上一次还没收尾，又没有「App 发起了加载」的信号：
        // 分不清这是迟到回调还是页面自己重新加载，按迟到回调算。钉住这个边界，免得以后有人以为它算重试。
        val tracker = LoadAttemptTracker()
        tracker.onHttpError(fallback, fatal = true)
        tracker.onPageStarted(fallback)
        assertEquals(Outcome.FAILED, tracker.onPageFinished(fallback))
    }

    @Test
    fun repeatedFinishWithoutANewStartIsIgnored() {
        // 有些 WebView 会把同一次加载的 onPageFinished 通知两遍：第二遍不算新的一页。
        val tracker = LoadAttemptTracker()
        tracker.onPageStarted(link)
        assertEquals(Outcome.SUCCEEDED, tracker.onPageFinished(link))
        assertEquals(Outcome.DUPLICATE, tracker.onPageFinished(link))
    }

    @Test
    fun finishWithoutAnyStart_isNotALoadedPage() {
        // 只有结束、没有开始：这次导航根本没开始（被新的导航顶掉，或者被拦下）。WebView 里还是原来那一页，
        // 不能当成加载完成——否则会去读原来那一页的状态码，还会多打一行 pageLoaded。
        val tracker = LoadAttemptTracker()
        assertEquals(Outcome.ABANDONED, tracker.onPageFinished("/slow"))
    }

    @Test
    fun navigationSupersededBeforeItStarts_isAbandonedAndDoesNotSpoilTheNewOne() {
        // 模拟器上实测的顺序：loadUrl(/slow) 还没拿到响应就 loadUrl(/home)，WebView 先通知 /slow 的「结束」，
        // 再来 /home 的「开始」「结束」。
        val slow = "https://zcode.z.ai/remote/v4?app_version=4.0.0&sid=s1"
        val home = "https://zcode.z.ai/remote/v4?app_version=4.0.0"
        val tracker = LoadAttemptTracker()
        tracker.onAppNavigation(home)
        assertEquals(Outcome.ABANDONED, tracker.onPageFinished(slow))
        tracker.onPageStarted(home)
        assertEquals(Outcome.SUCCEEDED, tracker.onPageFinished(home))
        // 被顶掉的那一次要是连「结束」都不来，新的一次照样是一次干净的导航。
        val tracker2 = LoadAttemptTracker()
        tracker2.onAppNavigation(home)
        tracker2.onPageStarted(home)
        assertEquals(Outcome.SUCCEEDED, tracker2.onPageFinished(home))
    }

    @Test
    fun blockedRedirectToAnotherScheme_isAbandoned() {
        // 302 跳到 weixin:// 被 shouldOverrideUrlLoading 拦下：WebView 也会通知一次那个地址的「结束」。
        val tracker = LoadAttemptTracker()
        assertEquals(Outcome.ABANDONED, tracker.onPageFinished("weixin://dl/business/?ticket=t"))
    }

    @Test
    fun abandonedNotificationRepeatedForTheSameAddressIsOnlyReportedOnce() {
        val tracker = LoadAttemptTracker()
        assertEquals(Outcome.ABANDONED, tracker.onPageFinished("weixin://dl/business/?ticket=t"))
        assertEquals(Outcome.DUPLICATE, tracker.onPageFinished("weixin://dl/business/?ticket=t"))
    }

    @Test
    fun abandonedNotificationBeforeTheRealLoad_leavesTheRealLoadClean() {
        val tracker = LoadAttemptTracker()
        assertEquals(Outcome.ABANDONED, tracker.onPageFinished("/old"))
        tracker.onPageStarted("https://zcode.z.ai/remote/v4")
        assertEquals(Outcome.SUCCEEDED, tracker.onPageFinished("https://zcode.z.ai/remote/v4"))
    }

    @Test
    fun finishAfterAFailedAttemptWithoutASecondFinishIsNotADuplicateSuccess() {
        // 失败收尾之后，同一个地址又来了一次没有开始的「结束」：不会把它当成加载成功。
        val tracker = LoadAttemptTracker()
        tracker.onNetworkError(link)
        assertEquals(Outcome.FAILED, tracker.onPageFinished(link))
        assertEquals(Outcome.DUPLICATE, tracker.onPageFinished(link))
    }

    @Test
    fun aNewDocumentStartingWhileTheOldOneIsUnfinishedBeginsANewAttempt() {
        val tracker = LoadAttemptTracker()
        tracker.onPageStarted(link)
        tracker.onPageStarted("https://zcode.z.ai/remote/v4?code=c&state=s")
        assertEquals(Outcome.SUCCEEDED, tracker.onPageFinished("https://zcode.z.ai/remote/v4?code=c&state=s"))
    }

    @Test
    fun failureOfAnUnfinishedAttemptDoesNotLeakIntoADifferentAddress() {
        val tracker = LoadAttemptTracker()
        tracker.onHttpError(link, fatal = true)
        tracker.onPageStarted(fallback)
        assertEquals(Outcome.SUCCEEDED, tracker.onPageFinished(fallback))
    }
}
