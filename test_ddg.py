import urllib.request, urllib.parse, re
data = urllib.parse.urlencode({'q': 'weather in london'}).encode('utf-8')
req = urllib.request.Request('https://html.duckduckgo.com/html/', data=data, headers={'User-Agent': 'Mozilla/5.0'})
html = urllib.request.urlopen(req).read().decode('utf-8')
print("Snippets:")
for match in re.findall(r'class="result__snippet[^>]*>(.*?)</a>', html, re.IGNORECASE | re.DOTALL)[:3]:
    print(re.sub(r'<[^>]+>', '', match).strip())
