# Test Koşum Raporları

Bu dizindeki raporlar `scripts/test-with-report.sh` tarafından gerçek Maven,
Surefire/Failsafe ve JaCoCo çıktılarından üretilir. Test sayısı veya kapsam değeri
elle yazılmaz.

Örnek:

```bash
./scripts/test-with-report.sh "tam doğrulama" verify
```

Ham Maven günlükleri `target/test-run-logs/` altında tutulur. Bu dizin derleme
çıktısı olduğu için kaynak kontrolüne alınmaz; Markdown raporu ilgili günlük
yolunu kanıt olarak kaydeder.
