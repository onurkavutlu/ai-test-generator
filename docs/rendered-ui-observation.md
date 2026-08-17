# Render edilmiş UI gözlemi

`FRONTEND_WEB` + Selenium üretiminde gözlem sırası şöyledir:

1. Hedef sayfanın ham HTML'i çekilir ve güvenli locator sözleşmesi çıkarılır.
2. `RENDERED_DOM_OBSERVATION_ENABLED=true` ise izole, gizli moddaki Chrome aynı
   URL'yi yalnız-okunur açar.
3. JavaScript sonrası **görünür** `input`, `button`, `select`, `textarea`, `a`
   ve `role=button` öğelerinden en fazla 25'i kayda alınır. Öncelik sırası
   `data-testid`, `id`, `name`dir.
4. Üretilen deterministik smoke testi, render edilmiş locator için explicit wait
   ve görünürlük assertion'ı; yalnız kaynakta bulunan locator için ise sadece DOM
   varlık assertion'ı kullanır.

Normal gözlem hiçbir öğeye tıklamaz, form doldurmaz, cookie kabul etmez veya input
değeri/HTML gövdesi kaydetmez. İsteğe bağlı kullanıcı-akışı keşfi (`FLOW_DISCOVERY_ENABLED`)
ayrı bir katmandır: yalnız kullanıcının metninde geçen, aynı origin'deki ve yan etkisiz
görünen menü/detay linklerini tıklar; başvuru, giriş, ödeme, sepet, sohbet ve form
akışlarını kesin olarak dışlar. Her adım sonrası URL veya görünür DOM değişimi ölçülür;
ölçülmeyen adım kanıt ve test olarak saklanmaz.

Doğrulanan akış `## OBSERVED USER FLOW` bölümüne request ile yazılır. Aynı bölüm,
`frontend_flow_learnings` tablosunda kaynak `request_id` ve origin ile kalıcılaşır.
Sonraki aynı-origin isteklerde en fazla üç önceki doğrulanmış akış LLM bağlamına
`## LEARNED FRONTEND FLOWS` olarak eklenir. Bunlar yeni bir davranışın kanıtı değildir:
LLM yalnız mevcut niyet ve güncel gözlem tarafından da desteklenen adımları kullanabilir.
Akışın doğrudan Selenium karşılığı ayrıca deterministik üretilir; LLM locator seçmez.

## İşletim sınırı

Render edilmiş DOM, hedef uygulamanın JavaScript'ini çalıştırır. Bu yüzden temel
profilde kapalıdır. Üretimde yalnız izole Selenium Grid'e
`SELENIUM_REMOTE_URL` tanımlanarak ve `RENDERED_DOM_OBSERVATION_ENABLED=true`
ile açılmalıdır. `local` profilinde geliştirme amacıyla varsayılan açıktır.
Akış tıklamaları için ayrıca `FLOW_DISCOVERY_ENABLED=true` gerekir; production'da
varsayılan kapalıdır ve izole Selenium Grid dışında açılmamalıdır.

## Vodafone ana sayfası örneği

17 Ağustos 2026'daki salt-okunur incelemede `https://www.vodafone.com.tr/`
sayfasında aşağıdaki gerçek, düşük-riskli smoke kontrolleri görüldü:

- Başlık: `Birlikte Mümkün | Vodafone Türkiye`
- Görünür arama alanı: `id=search-keyword-header`, `name=keyword`, etiket
  `Arama yap`
- Genel navigasyon linkleri: `/login`, `/kampanyalar`, `/bayi`

Pazarlama metni, ürün listesi veya fiyat gibi sık değişen içerikler assertion
değeri değildir. Örnekler yalnız sayfa yükleme, erişilebilir arama alanı ve
genel navigasyon hedefini kontrol etmelidir; giriş, arama gönderimi, alışveriş
veya sohbet gibi yan etkili akışlara dokunmamalıdır.
