# IntelliJ ile Karate feature testlerini calistirma

1. Projeyi IntelliJ IDEA ile acin.
2. Maven penceresinden projeyi reload edin.
3. `src/test/java/com/testgen/samples/LocalSeedDataKarateTest.java` dosyasini acin.
4. Sinif veya `runLocalSeedDataSamples` metodu uzerinden `Run` secin.

Bu runner su feature dosyalarini calistirir:

- `src/test/resources/samples/local-seed-data.feature`
- `src/test/resources/samples/llm-generation-audit.feature`
- `src/test/resources/samples/improvement-report.feature`

Feature dosyalari dis servise baglanmaz; bu nedenle lokal uygulama ayakta olmasa bile IntelliJ ve Maven uzerinden calisir.
