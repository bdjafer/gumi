#!/bin/sh
set -eu

if [ "$#" -lt 2 ] || [ "$#" -gt 3 ]; then
    echo "usage: $0 /path/to/BasedHardware-omi /new/materialized/worktree [profile]" >&2
    exit 64
fi

source_repo=$1
destination=$2
profile=${3:-canary-0001}
script_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
firmware_dir=$(CDPATH='' cd -- "$script_dir/.." && pwd)
expected_commit='85159556eac753a088c5efd1b419a5a867508e27'
functional_profile=false
provisioner_profile=false
reclaimer_profile=false

case "$profile" in
    canary-0001)
        patch_file="$firmware_dir/patches/0001-canary-identity.patch"
        patch_hash='afbcb090bcb5f3b4d74b4a95acf9752253cdb6c31fa6407904e4b6c416248c43'
        ;;
    recovery-only-0001)
        patch_file="$firmware_dir/patches/0002-recovery-only.patch"
        patch_hash='db1dfbb8dde3bdafc30da7fec7e7c4a4bcbdd13fc4a74293afeead3881ec48da'
        ;;
    capture-port-selftest-0001)
        patch_file="$firmware_dir/patches/0003-capture-port-selftest.patch"
        patch_hash='d2728f3bec5d63f4ef0b9ecc379cb79e1e2b24516ac928061c952a88f875d1ce'
        ;;
    functional-recording-0001)
        functional_profile=true
        patch_file="$firmware_dir/patches/0004-functional-recording.patch"
        patch_hash='282851f226dd8827a963da20fa56c8a0beab6c182b00c41410db3488bdd8b933'
        overlay_manifest="$firmware_dir/gumi/zephyr/omi-v3012/functional-recording-0001.manifest"
        overlay_manifest_hash='bf3f035c031438811a8bb41e7f76b33cbbf974318078dcae4558cdd1e4b1c844'
        ;;
    functional-recording-0002)
        functional_profile=true
        patch_file="$firmware_dir/patches/0004-functional-recording.patch"
        patch_hash='282851f226dd8827a963da20fa56c8a0beab6c182b00c41410db3488bdd8b933'
        overlay_manifest="$firmware_dir/gumi/zephyr/omi-v3012/functional-recording-0002.manifest"
        overlay_manifest_hash='ad030f1df863f970e92ecdaf36da3178a5f5acabf37326ce801e13998723f0c5'
        ;;
    functional-recording-0003)
        functional_profile=true
        patch_file="$firmware_dir/patches/0004-functional-recording.patch"
        patch_hash='282851f226dd8827a963da20fa56c8a0beab6c182b00c41410db3488bdd8b933'
        overlay_manifest="$firmware_dir/gumi/zephyr/omi-v3012/functional-recording-0003.manifest"
        overlay_manifest_hash='ed9f9964f3a5e160dd50b07ec35cdf88e8130b061f6a0c9bfaef5a7dfcea30ce'
        ;;
    functional-recording-0004)
        functional_profile=true
        patch_file="$firmware_dir/patches/0004-functional-recording.patch"
        patch_hash='282851f226dd8827a963da20fa56c8a0beab6c182b00c41410db3488bdd8b933'
        overlay_manifest="$firmware_dir/gumi/zephyr/omi-v3012/functional-recording-0004.manifest"
        overlay_manifest_hash='c95379f70ad198d7d7d286463752d7f0021d30fa5d95f4f1f125441d8a0b969f'
        ;;
    functional-recording-0005)
        functional_profile=true
        patch_file="$firmware_dir/patches/0006-functional-recording-v0005.patch"
        patch_hash='0e2c9082d453b7664d2796921b52e28316b4d55d71db724a29a3d75b5b4ee402'
        overlay_manifest="$firmware_dir/gumi/zephyr/omi-v3012/functional-recording-0005.manifest"
        overlay_manifest_hash='daedba504bac5e7230ff279e0719cbb492bd9a6929126773649f8b944e814169'
        ;;
    functional-recording-0006)
        functional_profile=true
        patch_file="$firmware_dir/patches/0007-functional-recording-v0006.patch"
        patch_hash='2e67a13bee0eda0d6b9092d13bf70e5764463a1c33213a609d8cfbbd61586d97'
        overlay_manifest="$firmware_dir/gumi/zephyr/omi-v3012/functional-recording-0006.manifest"
        overlay_manifest_hash='ab61c467acb425b57b5eefad91df9b9767eade4bad98257d41e313fefc7a9425'
        ;;
    functional-recording-0007)
        functional_profile=true
        patch_file="$firmware_dir/patches/0009-functional-recording-v0007.patch"
        patch_hash='6c01095ab3a4e098847e04c88e8559ace4516f245b0c0373f9e490fc8a660aeb'
        overlay_manifest="$firmware_dir/gumi/zephyr/omi-v3012/functional-recording-0007.manifest"
        overlay_manifest_hash='0ea31fcdffe16b588e3382809b5ad93f9c648807529c9662ad14e90132a38316'
        ;;
    recording-root-provisioner-0001)
        provisioner_profile=true
        patch_file="$firmware_dir/patches/0005-recording-root-provisioner.patch"
        patch_hash='35a3ecb78465bee587ecc3ea1331d9c1deed7d212757b4a83f2ccc0e51c6cb1c'
        overlay_manifest="$firmware_dir/gumi/zephyr/omi-v3012/recording-root-provisioner-0001.manifest"
        overlay_manifest_hash='991dc25ff70746d6116a4d7afe9e266f2c7cb8619b8e5cf879401b5b47e1c9f6'
        ;;
    legacy-storage-reclaimer-0001)
        reclaimer_profile=true
        patch_file="$firmware_dir/patches/0008-legacy-storage-reclaimer.patch"
        patch_hash='06faad731c0a87202c8184de72c6f9fae2476c45b50b38644ac32b105ab90573'
        overlay_manifest="$firmware_dir/gumi/zephyr/omi-v3012/legacy-storage-reclaimer-0001.manifest"
        overlay_manifest_hash='3c36f64402f4cd0cb6e8b4175609e408f0c2ca94e74bf55c5929b0891540751e'
        ;;
    legacy-storage-reclaimer-0002)
        reclaimer_profile=true
        patch_file="$firmware_dir/patches/0008-legacy-storage-reclaimer.patch"
        patch_hash='06faad731c0a87202c8184de72c6f9fae2476c45b50b38644ac32b105ab90573'
        overlay_manifest="$firmware_dir/gumi/zephyr/omi-v3012/legacy-storage-reclaimer-0002.manifest"
        overlay_manifest_hash='b3f4cdf0028a29b32b53f8e9a6e05428090af6d679411104373ca884ef4f337c'
        ;;
    kernel-link-probe-0001)
        patch_file="$firmware_dir/patches/1001-kernel-link-probe.patch"
        patch_hash='395a8ed71be04449981a453997fa028a82c03b70ac05b56a20c82cf3ad80993f'
        ;;
    mic-port-link-probe-0001)
        patch_file="$firmware_dir/patches/1002-zephyr-mic-port-link-probe.patch"
        patch_hash='ae7d5ce35bdc4fd6cf00e69964811d5f580a8fa6744d563dc9ce37433cfad997'
        ;;
    codec-port-link-probe-0001)
        patch_file="$firmware_dir/patches/1003-zephyr-codec-port-link-probe.patch"
        patch_hash='44279967c984ed4106e34b211729de22580419470711bf2629f0c2a8d4afc597'
        ;;
    *)
        echo "unknown firmware materialization profile: $profile" >&2
        exit 64
        ;;
esac

[ -d "$source_repo/.git" ] || {
    echo "source is not a Git checkout: $source_repo" >&2
    exit 1
}
[ ! -e "$destination" ] || {
    echo "destination already exists: $destination" >&2
    exit 1
}
[ -f "$patch_file" ] || {
    echo "firmware overlay is missing: $patch_file" >&2
    exit 1
}
actual_patch_hash=$(shasum -a 256 "$patch_file" | awk '{ print $1 }')
[ "$actual_patch_hash" = "$patch_hash" ] || {
    echo "firmware overlay does not match its pinned hash" >&2
    exit 1
}

actual_commit=$(git -C "$source_repo" rev-parse "$expected_commit^{commit}")
[ "$actual_commit" = "$expected_commit" ] || {
    echo "exact Omi v3.0.12 commit is unavailable" >&2
    exit 1
}

verify_sha256() {
    relative_path=$1
    expected_hash=$2
    actual_hash=$(git -C "$source_repo" show "$expected_commit:$relative_path" | shasum -a 256 | awk '{ print $1 }')
    [ "$actual_hash" = "$expected_hash" ] || {
        echo "$relative_path does not match the pinned upstream bytes" >&2
        exit 1
    }
}

verify_sha256 omi/firmware/omi/omi.conf \
    72838438b5999cd60c391f541f43cbc8d97ae1d403a913bba29b10c32ed04f65
verify_sha256 omi/firmware/omi/src/main.c \
    cb9108a9a7acc0141db8c11fda433bbef3727a008a60eaf475a6b3b5c27f0715
verify_sha256 omi/firmware/omi/CMakeLists.txt \
    f8ab8f8f9a0113964966c3d195ef03329957d30ffe16e3ec96a9be26db24fafe
verify_sha256 omi/firmware/bootloader/mcuboot/root-rsa-2048.pem \
    1fc912d30251b821f251e127d4daf7ba9338dd5c04e5af100abfb5b7c7d4c022

git -C "$source_repo" worktree add --detach "$destination" "$expected_commit"
git -C "$destination" apply --check "$patch_file"
git -C "$destination" apply "$patch_file"

if [ "$functional_profile" = true ] || \
   [ "$provisioner_profile" = true ] || \
   [ "$reclaimer_profile" = true ]; then
    actual_manifest_hash=$(shasum -a 256 "$overlay_manifest" | awk '{ print $1 }')
    [ "$actual_manifest_hash" = "$overlay_manifest_hash" ] || {
        echo "$profile overlay manifest does not match its pinned hash" >&2
        exit 1
    }
    kernel_source="$firmware_dir/gumi"
    application_destination="$destination/omi/firmware/omi"
    while read -r expected_hash source_relative destination_relative; do
        case "$expected_hash" in
            '' | \#*)
                continue
                ;;
        esac
        source_file="$kernel_source/$source_relative"
        destination_file="$application_destination/$destination_relative"
        actual_hash=$(shasum -a 256 "$source_file" | awk '{ print $1 }')
        [ "$actual_hash" = "$expected_hash" ] || {
            echo "$profile source does not match manifest: $source_relative" >&2
            exit 1
        }
        mkdir -p "$(dirname -- "$destination_file")"
        cp "$source_file" "$destination_file"
        copied_hash=$(shasum -a 256 "$destination_file" | awk '{ print $1 }')
        [ "$copied_hash" = "$expected_hash" ] || {
            echo "$profile materialization copy mismatch: $destination_relative" >&2
            exit 1
        }
    done < "$overlay_manifest"
fi

if [ "$profile" = kernel-link-probe-0001 ] || \
   [ "$profile" = mic-port-link-probe-0001 ] || \
   [ "$profile" = codec-port-link-probe-0001 ]; then
    kernel_source="$firmware_dir/gumi"
    kernel_destination="$destination/omi/firmware/omi/src/gumi"
    mkdir -p "$kernel_destination/include/gumi"
    cp "$kernel_source/include/gumi/button.h" "$kernel_destination/include/gumi/button.h"
    cp "$kernel_source/include/gumi/capture.h" "$kernel_destination/include/gumi/capture.h"
    cp "$kernel_source/include/gumi/feedback.h" "$kernel_destination/include/gumi/feedback.h"
    cp "$kernel_source/src/button.c" "$kernel_destination/button.c"
    cp "$kernel_source/src/capture.c" "$kernel_destination/capture.c"
    cp "$kernel_source/src/feedback.c" "$kernel_destination/feedback.c"
fi
if [ "$profile" = mic-port-link-probe-0001 ] || \
   [ "$profile" = codec-port-link-probe-0001 ]; then
    mkdir -p "$kernel_destination/zephyr/omi-v3012"
    cp \
        "$kernel_source/zephyr/omi-v3012/include/gumi/omi_v3012_mic.h" \
        "$kernel_destination/include/gumi/omi_v3012_mic.h"
    cp \
        "$kernel_source/zephyr/omi-v3012/src/mic_port.c" \
        "$kernel_destination/zephyr/omi-v3012/mic_port.c"
fi
if [ "$profile" = codec-port-link-probe-0001 ]; then
    cp \
        "$kernel_source/zephyr/omi-v3012/include/gumi/omi_v3012_codec.h" \
        "$kernel_destination/include/gumi/omi_v3012_codec.h"
    cp \
        "$kernel_source/zephyr/omi-v3012/src/codec_port.c" \
        "$kernel_destination/zephyr/omi-v3012/codec_port.c"
fi
if [ "$profile" = recovery-only-0001 ]; then
    kernel_source="$firmware_dir/gumi"
    kernel_destination="$destination/omi/firmware/omi/src/gumi"
    mkdir -p "$kernel_destination/include/gumi" "$kernel_destination/zephyr/omi-v3012"
    cp \
        "$kernel_source/include/gumi/recovery.h" \
        "$kernel_destination/include/gumi/recovery.h"
    cp \
        "$kernel_source/src/recovery.c" \
        "$kernel_destination/recovery.c"
    cp \
        "$kernel_source/zephyr/omi-v3012/include/gumi/omi_v3012_mic.h" \
        "$kernel_destination/include/gumi/omi_v3012_mic.h"
    cp \
        "$kernel_source/zephyr/omi-v3012/include/gumi/omi_v3012_recovery.h" \
        "$kernel_destination/include/gumi/omi_v3012_recovery.h"
    cp \
        "$kernel_source/zephyr/omi-v3012/src/mic_port.c" \
        "$kernel_destination/zephyr/omi-v3012/mic_port.c"
    cp \
        "$kernel_source/zephyr/omi-v3012/src/recovery_port.c" \
        "$kernel_destination/zephyr/omi-v3012/recovery_port.c"
    cp \
        "$kernel_source/zephyr/omi-v3012/src/recovery_only_main.c" \
        "$kernel_destination/zephyr/omi-v3012/recovery_only_main.c"
    cp \
        "$kernel_source/zephyr/omi-v3012/recovery-only.conf" \
        "$destination/omi/firmware/omi/gumi-recovery-only.conf"
fi
if [ "$profile" = capture-port-selftest-0001 ]; then
    kernel_source="$firmware_dir/gumi"
    kernel_destination="$destination/omi/firmware/omi/src/gumi"
    mkdir -p "$kernel_destination/include/gumi" "$kernel_destination/zephyr/omi-v3012"
    cp "$kernel_source/include/gumi/button.h" "$kernel_destination/include/gumi/button.h"
    cp \
        "$kernel_source/include/gumi/capture_selftest.h" \
        "$kernel_destination/include/gumi/capture_selftest.h"
    cp "$kernel_source/src/button.c" "$kernel_destination/button.c"
    cp \
        "$kernel_source/src/capture_selftest.c" \
        "$kernel_destination/capture_selftest.c"
    cp \
        "$kernel_source/zephyr/omi-v3012/include/gumi/omi_v3012_mic.h" \
        "$kernel_destination/include/gumi/omi_v3012_mic.h"
    cp \
        "$kernel_source/zephyr/omi-v3012/include/gumi/omi_v3012_codec.h" \
        "$kernel_destination/include/gumi/omi_v3012_codec.h"
    cp \
        "$kernel_source/zephyr/omi-v3012/include/gumi/omi_v3012_capture_selftest.h" \
        "$kernel_destination/include/gumi/omi_v3012_capture_selftest.h"
    cp \
        "$kernel_source/zephyr/omi-v3012/src/mic_port.c" \
        "$kernel_destination/zephyr/omi-v3012/mic_port.c"
    cp \
        "$kernel_source/zephyr/omi-v3012/src/codec_port.c" \
        "$kernel_destination/zephyr/omi-v3012/codec_port.c"
    cp \
        "$kernel_source/zephyr/omi-v3012/src/capture_selftest_io.c" \
        "$kernel_destination/zephyr/omi-v3012/capture_selftest_io.c"
    cp \
        "$kernel_source/zephyr/omi-v3012/src/capture_selftest_transport.c" \
        "$kernel_destination/zephyr/omi-v3012/capture_selftest_transport.c"
    cp \
        "$kernel_source/zephyr/omi-v3012/src/capture_selftest_main.c" \
        "$kernel_destination/zephyr/omi-v3012/capture_selftest_main.c"
    cp \
        "$kernel_source/zephyr/omi-v3012/capture-port-selftest.conf" \
        "$destination/omi/firmware/omi/gumi-capture-port-selftest.conf"
fi

if [ "$profile" = canary-0001 ]; then
    shasum -a 256 "$destination/omi/firmware/omi/omi.conf" | \
        grep '^68b75bb56d703bf31c1dd050a96c4b14c0b5e9afb189a9df0ecd6554f770b095  ' >/dev/null
    shasum -a 256 "$destination/omi/firmware/omi/src/main.c" | \
        grep '^90c6bf1250e41b165c0146d50f63ee0a5d4fb02287962f901c28df2729dc2732  ' >/dev/null
    expected_status=' M omi/firmware/omi/omi.conf
 M omi/firmware/omi/src/main.c'
elif [ "$profile" = recovery-only-0001 ]; then
    shasum -a 256 "$destination/omi/firmware/omi/CMakeLists.txt" | \
        grep '^a343fb99e0630b8b0e5f223ee82c6da8206c501cc2e1bf878c09a06b8870f777  ' >/dev/null
    shasum -a 256 "$destination/omi/firmware/omi/gumi-recovery-only.conf" | \
        grep '^296fde8971be8c7d98878265d9783f77771e83bcdd4eb95bb89da75c1c4b394d  ' >/dev/null
    shasum -a 256 "$kernel_destination/include/gumi/recovery.h" | \
        grep '^d29f6c3b261e321c205b3be78b4b51e01d3e534dac62a5dc84b41a0258aa3780  ' >/dev/null
    shasum -a 256 "$kernel_destination/recovery.c" | \
        grep '^875ccf5d7ef9e7a75b48d0f9422c93198c250a36273e47558182919d2b6fa9e5  ' >/dev/null
    shasum -a 256 "$kernel_destination/include/gumi/omi_v3012_mic.h" | \
        grep '^922b054be612aae87539c273da97eb3d4e2c00d616598cdb398176d03fd7a09b  ' >/dev/null
    shasum -a 256 "$kernel_destination/include/gumi/omi_v3012_recovery.h" | \
        grep '^86f0c32fff18fdde29c6dc73f87bad60cf25e6f760b0ce57c242592c1a39c2a5  ' >/dev/null
    shasum -a 256 "$kernel_destination/zephyr/omi-v3012/mic_port.c" | \
        grep '^0a1eb9f8e9748724f6b13071e19645803ef02730f3f93faa1fa21461fb70eafc  ' >/dev/null
    shasum -a 256 "$kernel_destination/zephyr/omi-v3012/recovery_port.c" | \
        grep '^feb40341f5904fa047cb8081f67377c9b01e55922853aa51b88fe7729be9a851  ' >/dev/null
    shasum -a 256 "$kernel_destination/zephyr/omi-v3012/recovery_only_main.c" | \
        grep '^90155497a28f2e6892bbe8596edc1de6319334cebe2486d93ee7b314781d317c  ' >/dev/null
    grep -F 'CONFIG_BT_DIS_SW_REV_STR="gumi-recovery-only-0001"' \
        "$destination/omi/firmware/omi/gumi-recovery-only.conf" >/dev/null
    expected_status=' M omi/firmware/omi/CMakeLists.txt
?? omi/firmware/omi/gumi-recovery-only.conf
?? omi/firmware/omi/src/gumi/include/gumi/omi_v3012_mic.h
?? omi/firmware/omi/src/gumi/include/gumi/omi_v3012_recovery.h
?? omi/firmware/omi/src/gumi/include/gumi/recovery.h
?? omi/firmware/omi/src/gumi/recovery.c
?? omi/firmware/omi/src/gumi/zephyr/omi-v3012/mic_port.c
?? omi/firmware/omi/src/gumi/zephyr/omi-v3012/recovery_only_main.c
?? omi/firmware/omi/src/gumi/zephyr/omi-v3012/recovery_port.c'
elif [ "$profile" = capture-port-selftest-0001 ]; then
    shasum -a 256 "$destination/omi/firmware/omi/CMakeLists.txt" | \
        grep '^927978102f59dc3e973e6c49ce9d4698ec18f29c8a3c4dfccb667b1450bf8072  ' >/dev/null
    shasum -a 256 "$destination/omi/firmware/omi/gumi-capture-port-selftest.conf" | \
        grep '^8cc9df7470b0785c13f50f0ec16010bb339146b1d8b5baadf5c01bdfad5026fe  ' >/dev/null
    shasum -a 256 "$kernel_destination/include/gumi/button.h" | \
        grep '^3d687a7aa02b652d4ea21d4269f36504df017fb6e4d9670a84039502b041c633  ' >/dev/null
    shasum -a 256 "$kernel_destination/include/gumi/capture_selftest.h" | \
        grep '^a763b5aa3dad3e3dd455938f5ebb62b249965877d77b69587c2a27161059b7b9  ' >/dev/null
    shasum -a 256 "$kernel_destination/button.c" | \
        grep '^61c389380dc16056a1bad9cb341bc488fd3ffebbfd675faba916014a4e939262  ' >/dev/null
    shasum -a 256 "$kernel_destination/capture_selftest.c" | \
        grep '^aa0836c0652bd0d2ab58197ba8b8fe726cdefda7ec0a863229635e9e508bf57f  ' >/dev/null
    shasum -a 256 "$kernel_destination/include/gumi/omi_v3012_mic.h" | \
        grep '^922b054be612aae87539c273da97eb3d4e2c00d616598cdb398176d03fd7a09b  ' >/dev/null
    shasum -a 256 "$kernel_destination/include/gumi/omi_v3012_codec.h" | \
        grep '^364880210dcf280c1717bc179300f752b937f39516743ffeefad9e2d73c46aee  ' >/dev/null
    shasum -a 256 "$kernel_destination/include/gumi/omi_v3012_capture_selftest.h" | \
        grep '^ccc5695a4a3664eb703bf467071da844fcb46f9f44fc8a2a101567685e4ce0cd  ' >/dev/null
    shasum -a 256 "$kernel_destination/zephyr/omi-v3012/mic_port.c" | \
        grep '^0a1eb9f8e9748724f6b13071e19645803ef02730f3f93faa1fa21461fb70eafc  ' >/dev/null
    shasum -a 256 "$kernel_destination/zephyr/omi-v3012/codec_port.c" | \
        grep '^e9df0036d3efd9ed771311b24da30c9647b06e1011b70c7cb75af67e43d8fa20  ' >/dev/null
    shasum -a 256 "$kernel_destination/zephyr/omi-v3012/capture_selftest_io.c" | \
        grep '^782155908ff5ad38eb87f4b23e94f4edc891dd7f5df360cfc061fd2e1d52c69e  ' >/dev/null
    shasum -a 256 "$kernel_destination/zephyr/omi-v3012/capture_selftest_transport.c" | \
        grep '^b9e72ccf921b3940e3d8f608fec81ca0fb61ac008f98f27f647d341e7d0e4196  ' >/dev/null
    shasum -a 256 "$kernel_destination/zephyr/omi-v3012/capture_selftest_main.c" | \
        grep '^851e65e475a5030087173be1de1f8fa3ad9ceee2cf30c3b91f027bb7394f7435  ' >/dev/null
    grep -F 'CONFIG_BT_DIS_SW_REV_STR="gumi-capture-port-selftest-0001"' \
        "$destination/omi/firmware/omi/gumi-capture-port-selftest.conf" >/dev/null
    expected_status=' M omi/firmware/omi/CMakeLists.txt
?? omi/firmware/omi/gumi-capture-port-selftest.conf
?? omi/firmware/omi/src/gumi/button.c
?? omi/firmware/omi/src/gumi/capture_selftest.c
?? omi/firmware/omi/src/gumi/include/gumi/button.h
?? omi/firmware/omi/src/gumi/include/gumi/capture_selftest.h
?? omi/firmware/omi/src/gumi/include/gumi/omi_v3012_capture_selftest.h
?? omi/firmware/omi/src/gumi/include/gumi/omi_v3012_codec.h
?? omi/firmware/omi/src/gumi/include/gumi/omi_v3012_mic.h
?? omi/firmware/omi/src/gumi/zephyr/omi-v3012/capture_selftest_io.c
?? omi/firmware/omi/src/gumi/zephyr/omi-v3012/capture_selftest_main.c
?? omi/firmware/omi/src/gumi/zephyr/omi-v3012/capture_selftest_transport.c
?? omi/firmware/omi/src/gumi/zephyr/omi-v3012/codec_port.c
?? omi/firmware/omi/src/gumi/zephyr/omi-v3012/mic_port.c'
elif [ "$functional_profile" = true ]; then
    grep -F 'Gumi functional stage: the stock application graph is not linked.' \
        "$destination/omi/firmware/omi/CMakeLists.txt" >/dev/null
    grep -F "CONFIG_BT_DIS_SW_REV_STR=\"gumi-$profile\"" \
        "$destination/omi/firmware/omi/gumi-functional-recording.conf" >/dev/null
    functional_untracked=$(awk '
        $1 !~ /^#/ && NF == 3 {
            print "?? omi/firmware/omi/" $3
        }
    ' "$overlay_manifest" | LC_ALL=C sort)
    expected_status=$(printf '%s\n%s' \
        ' M omi/firmware/omi/CMakeLists.txt' \
        "$functional_untracked")
elif [ "$provisioner_profile" = true ]; then
    grep -F 'One-shot recording-root provisioner: no stock functional source is linked.' \
        "$destination/omi/firmware/omi/CMakeLists.txt" >/dev/null
    grep -F 'CONFIG_BT_DIS_SW_REV_STR="gumi-recording-root-provisioner-0001"' \
        "$destination/omi/firmware/omi/gumi-recording-root-provisioner.conf" >/dev/null
    provisioner_untracked=$(awk '
        $1 !~ /^#/ && NF == 3 {
            print "?? omi/firmware/omi/" $3
        }
    ' "$overlay_manifest" | LC_ALL=C sort)
    expected_status=$(printf '%s\n%s' \
        ' M omi/firmware/omi/CMakeLists.txt' \
        "$provisioner_untracked")
elif [ "$reclaimer_profile" = true ]; then
    grep -F 'One-purpose legacy archive reclaimer: no stock functional source is linked.' \
        "$destination/omi/firmware/omi/CMakeLists.txt" >/dev/null
    grep -F "CONFIG_BT_DIS_SW_REV_STR=\"gumi-$profile\"" \
        "$destination/omi/firmware/omi/gumi-legacy-storage-reclaimer.conf" >/dev/null
    grep -F 'CONFIG_FILE_SYSTEM_MKFS=n' \
        "$destination/omi/firmware/omi/gumi-legacy-storage-reclaimer.conf" >/dev/null
    reclaimer_untracked=$(awk '
        $1 !~ /^#/ && NF == 3 {
            print "?? omi/firmware/omi/" $3
        }
    ' "$overlay_manifest" | LC_ALL=C sort)
    expected_status=$(printf '%s\n%s' \
        ' M omi/firmware/omi/CMakeLists.txt' \
        "$reclaimer_untracked")
else
    shasum -a 256 "$destination/omi/firmware/omi/src/gumi/include/gumi/button.h" | \
        grep '^3d687a7aa02b652d4ea21d4269f36504df017fb6e4d9670a84039502b041c633  ' >/dev/null
    shasum -a 256 "$destination/omi/firmware/omi/src/gumi/include/gumi/capture.h" | \
        grep '^a703d085133c886fb46679451f743d1353431bb42548d9d03e09002bdbed7dfd  ' >/dev/null
    shasum -a 256 "$destination/omi/firmware/omi/src/gumi/include/gumi/feedback.h" | \
        grep '^c79b7f224d7651c1b92a46dd321f11c2cf20707302f7fe990a0ffa8687ce7e98  ' >/dev/null
    shasum -a 256 "$destination/omi/firmware/omi/src/gumi/button.c" | \
        grep '^61c389380dc16056a1bad9cb341bc488fd3ffebbfd675faba916014a4e939262  ' >/dev/null
    shasum -a 256 "$destination/omi/firmware/omi/src/gumi/capture.c" | \
        grep '^625d9ea850575886000fa7f2138e079962324820be6a966ed10624d0439f5a2a  ' >/dev/null
    shasum -a 256 "$destination/omi/firmware/omi/src/gumi/feedback.c" | \
        grep '^f0fffc6d20a658d377150f1e55fe63f2f5c9d5b3e7dcd81f11b1936610b66f0c  ' >/dev/null
    if [ "$profile" = kernel-link-probe-0001 ]; then
        shasum -a 256 "$destination/omi/firmware/omi/CMakeLists.txt" | \
            grep '^a47fb0ff45363f65573b1da8994a7320d3571a7ddea5ff3735e855b054153e66  ' >/dev/null
        shasum -a 256 "$destination/omi/firmware/omi/omi.conf" | \
            grep '^aadfdeee6f376255693d8cf2c68e0459fa5b9a0311df3bed3dfc46cf7c31586e  ' >/dev/null
        grep -F 'CONFIG_BT_DIS_SW_REV_STR="gumi-kernel-link-probe-0001"' \
            "$destination/omi/firmware/omi/omi.conf" >/dev/null
        expected_status=' M omi/firmware/omi/CMakeLists.txt
 M omi/firmware/omi/omi.conf
?? omi/firmware/omi/src/gumi/button.c
?? omi/firmware/omi/src/gumi/capture.c
?? omi/firmware/omi/src/gumi/feedback.c
?? omi/firmware/omi/src/gumi/include/gumi/button.h
?? omi/firmware/omi/src/gumi/include/gumi/capture.h
?? omi/firmware/omi/src/gumi/include/gumi/feedback.h'
    elif [ "$profile" = mic-port-link-probe-0001 ]; then
        shasum -a 256 "$destination/omi/firmware/omi/CMakeLists.txt" | \
            grep '^1facfeaefc365a10e7bc1d57129eeae86fd21924f02cd17ea2e7896d5fb24249  ' >/dev/null
        shasum -a 256 "$destination/omi/firmware/omi/omi.conf" | \
            grep '^4981597a50cbe1fa85f1afd2f966a352d3f03e4687478c408c579396b21924c0  ' >/dev/null
        shasum -a 256 \
            "$destination/omi/firmware/omi/src/gumi/include/gumi/omi_v3012_mic.h" | \
            grep '^922b054be612aae87539c273da97eb3d4e2c00d616598cdb398176d03fd7a09b  ' >/dev/null
        shasum -a 256 \
            "$destination/omi/firmware/omi/src/gumi/zephyr/omi-v3012/mic_port.c" | \
            grep '^0a1eb9f8e9748724f6b13071e19645803ef02730f3f93faa1fa21461fb70eafc  ' >/dev/null
        grep -F 'CONFIG_BT_DIS_SW_REV_STR="gumi-mic-port-probe-0001"' \
            "$destination/omi/firmware/omi/omi.conf" >/dev/null
        expected_status=' M omi/firmware/omi/CMakeLists.txt
 M omi/firmware/omi/omi.conf
?? omi/firmware/omi/src/gumi/button.c
?? omi/firmware/omi/src/gumi/capture.c
?? omi/firmware/omi/src/gumi/feedback.c
?? omi/firmware/omi/src/gumi/include/gumi/button.h
?? omi/firmware/omi/src/gumi/include/gumi/capture.h
?? omi/firmware/omi/src/gumi/include/gumi/feedback.h
?? omi/firmware/omi/src/gumi/include/gumi/omi_v3012_mic.h
?? omi/firmware/omi/src/gumi/zephyr/omi-v3012/mic_port.c'
    else
        shasum -a 256 "$destination/omi/firmware/omi/CMakeLists.txt" | \
            grep '^f1be6de1ae45329d21eb07caab68be0cf0b1930e7bafb67b357c2209d9fb7112  ' >/dev/null
        shasum -a 256 "$destination/omi/firmware/omi/omi.conf" | \
            grep '^2ca433e204cf4293a2d7eeab77bd7a4058020b0313190c6909a35ba9f916609f  ' >/dev/null
        shasum -a 256 \
            "$destination/omi/firmware/omi/src/gumi/include/gumi/omi_v3012_mic.h" | \
            grep '^922b054be612aae87539c273da97eb3d4e2c00d616598cdb398176d03fd7a09b  ' >/dev/null
        shasum -a 256 \
            "$destination/omi/firmware/omi/src/gumi/zephyr/omi-v3012/mic_port.c" | \
            grep '^0a1eb9f8e9748724f6b13071e19645803ef02730f3f93faa1fa21461fb70eafc  ' >/dev/null
        shasum -a 256 \
            "$destination/omi/firmware/omi/src/gumi/include/gumi/omi_v3012_codec.h" | \
            grep '^364880210dcf280c1717bc179300f752b937f39516743ffeefad9e2d73c46aee  ' >/dev/null
        shasum -a 256 \
            "$destination/omi/firmware/omi/src/gumi/zephyr/omi-v3012/codec_port.c" | \
            grep '^e9df0036d3efd9ed771311b24da30c9647b06e1011b70c7cb75af67e43d8fa20  ' >/dev/null
        grep -F 'CONFIG_BT_DIS_SW_REV_STR="gumi-codec-port-probe-0001"' \
            "$destination/omi/firmware/omi/omi.conf" >/dev/null
        expected_status=' M omi/firmware/omi/CMakeLists.txt
 M omi/firmware/omi/omi.conf
?? omi/firmware/omi/src/gumi/button.c
?? omi/firmware/omi/src/gumi/capture.c
?? omi/firmware/omi/src/gumi/feedback.c
?? omi/firmware/omi/src/gumi/include/gumi/button.h
?? omi/firmware/omi/src/gumi/include/gumi/capture.h
?? omi/firmware/omi/src/gumi/include/gumi/feedback.h
?? omi/firmware/omi/src/gumi/include/gumi/omi_v3012_codec.h
?? omi/firmware/omi/src/gumi/include/gumi/omi_v3012_mic.h
?? omi/firmware/omi/src/gumi/zephyr/omi-v3012/codec_port.c
?? omi/firmware/omi/src/gumi/zephyr/omi-v3012/mic_port.c'
    fi
fi
actual_status=$(git -C "$destination" status --short --untracked-files=all -- omi/firmware)
[ "$actual_status" = "$expected_status" ] || {
    echo "materialized worktree does not contain exactly the $profile overlay" >&2
    printf 'expected:\n%s\nactual:\n%s\n' "$expected_status" "$actual_status" >&2
    exit 1
}

echo "materialized=$destination"
echo "upstream_commit=$expected_commit"
echo "overlay=$profile"
if [ "$profile" = kernel-link-probe-0001 ] || \
   [ "$profile" = mic-port-link-probe-0001 ] || \
   [ "$profile" = codec-port-link-probe-0001 ]; then
    echo "physical_use_forbidden=true"
fi
if [ "$profile" = recovery-only-0001 ]; then
    echo "behavioral_candidate=offline-unqualified"
    echo "physical_use_forbidden=true"
fi
if [ "$profile" = capture-port-selftest-0001 ]; then
    echo "behavioral_candidate=offline-unqualified"
    echo "physical_use_forbidden=true"
fi
if [ "$functional_profile" = true ]; then
    echo "behavioral_candidate=offline-unqualified"
    echo "physical_use_forbidden=true"
fi
if [ "$provisioner_profile" = true ]; then
    echo "behavioral_candidate=offline-unqualified"
    echo "physical_use_forbidden=true"
fi
if [ "$reclaimer_profile" = true ]; then
    echo "behavioral_candidate=offline-unqualified"
    echo "destructive_scope=/SD:/audio/a01.txt"
    echo "format_capability=false"
    echo "physical_use_forbidden=true"
fi
