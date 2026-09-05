"""汇总 lint 文本报告：按规则统计条数，并列出每条规则命中的文件。

写成脚本文件而不是内联到 bash：heredoc 会吃掉 Python 里的反斜杠转义
（'\\' 到了 Python 手上变成 '\'，直接 SyntaxError）。
"""
import collections
import io
import re
import sys

path = sys.argv[1] if len(sys.argv) > 1 else (
    'app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt'
)
sep = chr(92)  # 反斜杠，避免字面量里出现转义

text = io.open(path, encoding='utf-8', errors='replace').read()
# 路径开头是 Windows 盘符（D:\...），所以不能用 [^:]* 匹配路径段 —— 会停在 D 后面那个冒号。
# 改成非贪婪 .+? 一路吃到 `:<行号>: Warning|Error:` 这个更有辨识度的锚点。
rows = re.findall(r'^(.+?):(\d+): (Warning|Error): (.*?)\[(\w+)\]\s*$', text, re.M)


def short(p):
    return p.replace(sep, '/').split('/app/')[-1]


by_rule = collections.Counter(r[4] for r in rows)
for rule, n in by_rule.most_common():
    files = collections.Counter(short(r[0]) for r in rows if r[4] == rule)
    listed = '; '.join('%s(%d)' % (f, c) for f, c in files.most_common(8))
    print('%-26s %3d  %s' % (rule, n, listed))
print('')
print('合计 %d 条，%d 种规则' % (sum(by_rule.values()), len(by_rule)))
