/**
 * Karate genel yapılandırması — tüm feature'lar için ortak.
 *
 * Değerler şu sırayla çözülür:  -D sistem özelliği  →  ortam değişkeni  →  varsayılan.
 * Böylece ortam değiştirmek için dosya düzenlemek gerekmez:
 *
 *   ./mvnw test -Dtest=BtkBorcAlacakRunner -Dbtk.baseUrl=https://baska-ortam/...
 *   BTK_PASSWORD=... ./mvnw test -Dtest=BtkBorcAlacakRunner
 *
 * DİKKAT: Bu dosya classpath kökündedir, yani HER feature koşumunda çalışır.
 * Bu yüzden hiçbir koşulda istisna fırlatmaz; eksik değer null kalır ve ilgili
 * feature kendi ön koşulunu kendisi denetler.
 */
function fn() {

  function cfg(prop, envVar, fallback) {
    var v = karate.properties[prop];
    if (v === null || v === undefined || v === '') {
      v = java.lang.System.getenv(envVar);
    }
    if (v === null || v === undefined || v === '') {
      v = fallback;
    }
    return v;
  }

  var config = {

    // ── Bu uygulamanın kendi API'si (mevcut smoke/api/regression feature'ları) ──
    baseUrl: cfg('baseUrl', 'APP_BASE_URL', 'http://localhost:8080'),

    // ── BTK Borç/Alacak — abonelik sorgulama (test ortamı) ──
    btkBaseUrl: cfg('btk.baseUrl', 'BTK_BASE_URL',
      'https://cw-btk-borcalacak-service-eai-inquiry-services.apps.mobil-test.vodafone.local'),
    btkSoapAction: cfg('btk.soapAction', 'BTK_SOAP_ACTION',
      'http://standart.turkiye.gov.tr/aboneliksorgulama/v2/abonelikSorgula'),

    operatorCode: cfg('btk.operatorCode', 'BTK_OPERATOR_CODE', 'VODAFONEGSM'),
    username:     cfg('btk.username',     'BTK_USERNAME',     'webuser'),
    password:     cfg('btk.password',     'BTK_PASSWORD',     'q0VaPoVRIXalTU5iQjzwg'),
    ipAddress:    cfg('btk.ipAddress',    'BTK_IP_ADDRESS',   '100.127.7.97'),
    trIdentityNo: cfg('btk.trIdentityNo', 'BTK_TR_IDENTITY',  '81019536148'),
    pageNo:       cfg('btk.pageNo',       'BTK_PAGE_NO',      '1')
  };

  // Kurumsal ağda yanıt gecikebilir; varsayılan Karate zaman aşımı kısa kalıyor.
  karate.configure('connectTimeout', 30000);
  karate.configure('readTimeout',    60000);

  return config;
}
