package com.lululu.zcoderemote.web

/**
 * 软键盘弹出后，把页面上正在输入的那个框滚到看得见的地方。
 *
 * 为什么要自己做：铺到系统栏下面之后窗口不随键盘缩，外壳把键盘的高度让出来、WebView 变矮，
 * 这时页面不会自己把输入框滚回可见区域。模拟器上实测：WebView 从 842dp 缩到 523dp 后滚动位置纹丝不动，
 * 输入框留在键盘后面。
 *
 * 脚本只滚动，不改页面：获得焦点的不是可输入的元素时什么都不做。纯逻辑，不引用 android.*。
 */
object InputProbe {
    const val REVEAL_FOCUSED_SCRIPT: String =
        "(function(){try{" +
            "var e=document.activeElement;if(!e)return '';" +
            "var t=(e.tagName||'').toLowerCase();" +
            "var editable=t==='textarea'||t==='select'||e.isContentEditable;" +
            "if(t==='input'){var y=(e.type||'text').toLowerCase();" +
            "editable=!/^(button|submit|reset|checkbox|radio|file|image|hidden|range|color)$/.test(y);}" +
            "if(!editable)return '';" +
            "e.scrollIntoView({block:'center',inline:'nearest',behavior:'instant'});return 'ok'" +
            "}catch(x){return ''}})()"
}
