# Minha Central de Câmeras — Android V1

Projeto Android **nativo em Kotlin**, pronto para abrir no Android Studio ou enviar para um repositório GitHub.

## Recursos implementados
- Cadastro, edição e exclusão de câmeras locais
- Leitura de QR Code (ZXing)
- Busca ONVIF via WS-Discovery UDP na rede Wi-Fi (identifica IP; não configura credenciais ONVIF)
- Reprodução de RTSP no próprio Android com Media3/ExoPlayer, incluindo tentativa de autenticação via URL
- Acesso às configurações de Wi-Fi do Android
- Lista de câmeras persistida no dispositivo, sem guardar a senha RTSP

## Gerar APK usando GitHub
1. Crie um repositório GitHub e envie **o conteúdo desta pasta** para a raiz do repositório (inclusive `.github/workflows/android.yml`).
2. Abra a aba **Actions** e execute o fluxo **Android APK** (ou faça push em `main`).
3. Ao concluir com sucesso, abra a execução e baixe o artefato `MinhaCentralCameras-debug-apk`.
4. Extraia o APK e instale no Android, autorizando a instalação de aplicativos dessa origem quando solicitado.

## Limites e segurança
- **Não é um instalador universal de qualquer câmera.** O QR Code pode conter somente ID proprietário, insuficiente para configurar uma câmera.
- ONVIF é usado para descoberta de IP; ainda não há autenticação ONVIF, configuração automática de rede, PTZ ou captura de URL via GetStreamUri.
- Reprodução RTSP depende de credenciais, suporte do dispositivo e da biblioteca Media3. Não há garantia de reprodução em modelos específicos.
- A versão atual **não oferece contas de clientes, servidor de nuvem, transmissão remota, gravação ou gateway**.
- O aplicativo é para uso em rede local confiável. Não exponha portas RTSP/ONVIF diretamente à internet.
- A senha RTSP não é persistida. Os demais dados ficam no armazenamento privado local.
- O APK gerado pelo GitHub é uma compilação de depuração, não um pacote assinado para distribuição comercial na Play Store.
- A compilação não foi executada neste ambiente; se a Action falhar, envie o log para correção.

## Requisitos
Android Studio recente, JDK 17, Android SDK 35, Gradle 8.9. O GitHub Actions instala o ambiente necessário.
