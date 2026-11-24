# 混淆与收缩规则。
#
# 当前 release 关闭了混淆（app/build.gradle.kts 里 isMinifyEnabled = false），本文件不起作用，
# 留着是为了将来打开混淆时不用再找入口。打开混淆前必须先补规则，否则会出运行期故障：
#
#   1. 通过名字反射访问的类（WebView 的 JS 桥、@JavascriptInterface 方法所在的类、
#      androidx.webkit 的 WebMessageListener 回调）。
#   2. 靠 Kotlin 反射或序列化读写的字段（DataStore 的 Parcelable、JSON 模型类）。
#
# 把规则写在下面。
