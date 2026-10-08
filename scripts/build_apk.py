"""Build with JDK 17 + Android SDK 35; no Gradle/Maven downloads. Works on Windows too."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]

def run(*args):
    subprocess.run([str(a) for a in args], check=True, cwd=ROOT)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--sdk', default=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT'))
    parser.add_argument('--keystore', default=str(Path.home() / '.android' / 'academy-attendance-debug.keystore'))
    parser.add_argument('--output', default=str(ROOT / 'out' / 'AcademyAttendance.apk'))
    args = parser.parse_args()
    if not args.sdk:
        parser.error('Set ANDROID_HOME or pass --sdk (Android SDK Platform 35 + Build Tools 35.0.0).')
    sdk = Path(args.sdk)
    tools = sdk / 'build-tools' / '35.0.0'
    android = sdk / 'platforms' / 'android-35' / 'android.jar'
    win = os.name == 'nt'
    exe = lambda name: tools / (name + ('.exe' if win else ''))
    java_home = os.environ.get('JAVA_HOME')
    java = lambda name: str(Path(java_home) / 'bin' / (name + ('.exe' if win else ''))) if java_home else name
    for path in [android, exe('aapt2'), exe('zipalign'), tools / 'lib' / 'd8.jar', tools / 'lib' / 'apksigner.jar']:
        if not path.is_file():
            parser.error(f'Missing SDK component: {path}')
    build = ROOT / 'out' / 'build'
    if build.exists():
        shutil.rmtree(build)
    for part in ['classes', 'generated', 'dex']:
        (build / part).mkdir(parents=True, exist_ok=True)
    src = ROOT / 'app' / 'src' / 'main'
    ET.register_namespace('android', 'http://schemas.android.com/apk/res/android')
    manifest = ET.parse(src / 'AndroidManifest.xml')
    manifest.getroot().set('package', 'kr.academy.attendance')
    manifest.write(build / 'AndroidManifest.xml', encoding='utf-8', xml_declaration=True)
    run(exe('aapt2'), 'compile', '--dir', src / 'res', '-o', build / 'resources.zip')
    run(exe('aapt2'), 'link', '-o', build / 'base.apk', '-I', android, '--manifest', build / 'AndroidManifest.xml', '-R', build / 'resources.zip', '-A', src / 'assets', '--java', build / 'generated', '--min-sdk-version', '26', '--target-sdk-version', '35', '--auto-add-overlay')
    sources = sorted((src / 'java').rglob('*.java')) + sorted((build / 'generated').rglob('*.java'))
    run(java('java'), '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-classpath', android, '-d', build / 'classes', *sources)
    classes_jar = build / 'classes.jar'
    with zipfile.ZipFile(classes_jar, 'w', zipfile.ZIP_DEFLATED) as jar:
        for file in sorted((build / 'classes').rglob('*.class')):
            jar.write(file, file.relative_to(build / 'classes').as_posix())
    run(java('java'), '-cp', tools / 'lib' / 'd8.jar', 'com.android.tools.r8.D8', '--release', '--min-api', '26', '--lib', android, '--output', build / 'dex', classes_jar)
    unsigned = build / 'unsigned.apk'
    shutil.copyfile(build / 'base.apk', unsigned)
    with zipfile.ZipFile(unsigned, 'a', zipfile.ZIP_DEFLATED) as apk:
        for file in sorted((build / 'dex').glob('*.dex')):
            apk.write(file, file.name)
    aligned = build / 'aligned.apk'
    run(exe('zipalign'), '-f', '-p', '4', unsigned, aligned)
    key = Path(args.keystore).resolve()
    # This is a private, locally generated development key. Never commit it.
    # Reuse the same key for updates so Android can retain the installed app's data.
    if not key.exists():
        key.parent.mkdir(parents=True, exist_ok=True)
        run(java('keytool'), '-genkeypair', '-keystore', key, '-storepass', 'android', '-keypass', 'android', '-alias', 'attendance', '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000', '-dname', 'CN=Academy Attendance Development, O=Local Development, C=KR')
        if not win:
            key.chmod(0o600)
    output = Path(args.output).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    run(java('java'), '-jar', tools / 'lib' / 'apksigner.jar', 'sign', '--ks', key, '--ks-key-alias', 'attendance', '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', output, aligned)
    run(java('java'), '-jar', tools / 'lib' / 'apksigner.jar', 'verify', '--verbose', output)
    run(exe('zipalign'), '-c', '4', output)
    print(f'APK: {output}')

if __name__ == '__main__':
    main()
