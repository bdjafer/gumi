#!/bin/sh
set -eu

if [ "$#" -lt 3 ] || [ "$#" -gt 4 ]; then
    echo "usage: $0 /materialized/omi /ncs-v2.9.0-west-workspace build-directory-name [profile]" >&2
    exit 64
fi

source_repo=$(CDPATH='' cd -- "$1" && pwd)
ncs_workspace=$(CDPATH='' cd -- "$2" && pwd)
script_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
firmware_dir=$(CDPATH='' cd -- "$script_dir/.." && pwd)
build_name=$3
profile=${4:-canary-0001}
expected_commit='85159556eac753a088c5efd1b419a5a867508e27'
toolchain_image='ghcr.io/nrfconnect/sdk-nrf-toolchain:v2.9.0@sha256:7e9b61475ca05b8517079bedc8645479101fdaa17de2d0fa06a1633288112db2'
functional_profile=false
provisioner_profile=false
reclaimer_profile=false

case "$build_name" in
    '' | */* | .* | *[!A-Za-z0-9._-]*)
        echo "build directory name must be one safe path component" >&2
        exit 1
        ;;
esac

[ "$(git -C "$source_repo" rev-parse HEAD)" = "$expected_commit" ] || {
    echo "materialized source is not based on the pinned Omi commit" >&2
    exit 1
}
profile_config_relative='omi/firmware/omi/omi.conf'
case "$profile" in
    canary-0001)
        expected_sw_revision='gumi-canary-0001'
        expected_source_status=' M omi/firmware/omi/omi.conf
 M omi/firmware/omi/src/main.c'
        ;;
    recovery-only-0001)
        expected_sw_revision='gumi-recovery-only-0001'
        profile_config_relative='omi/firmware/omi/gumi-recovery-only.conf'
        expected_source_status=' M omi/firmware/omi/CMakeLists.txt
?? omi/firmware/omi/gumi-recovery-only.conf
?? omi/firmware/omi/src/gumi/include/gumi/omi_v3012_mic.h
?? omi/firmware/omi/src/gumi/include/gumi/omi_v3012_recovery.h
?? omi/firmware/omi/src/gumi/include/gumi/recovery.h
?? omi/firmware/omi/src/gumi/recovery.c
?? omi/firmware/omi/src/gumi/zephyr/omi-v3012/mic_port.c
?? omi/firmware/omi/src/gumi/zephyr/omi-v3012/recovery_only_main.c
?? omi/firmware/omi/src/gumi/zephyr/omi-v3012/recovery_port.c'
        ;;
    capture-port-selftest-0001)
        expected_sw_revision='gumi-capture-port-selftest-0001'
        profile_config_relative='omi/firmware/omi/gumi-capture-port-selftest.conf'
        expected_source_status=' M omi/firmware/omi/CMakeLists.txt
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
        ;;
    functional-recording-0001)
        functional_profile=true
        expected_sw_revision='gumi-functional-recording-0001'
        profile_config_relative='omi/firmware/omi/gumi-functional-recording.conf'
        profile_manifest="$firmware_dir/gumi/zephyr/omi-v3012/functional-recording-0001.manifest"
        profile_manifest_hash='bf3f035c031438811a8bb41e7f76b33cbbf974318078dcae4558cdd1e4b1c844'
        functional_untracked=$(awk '
            $1 !~ /^#/ && NF == 3 {
                print "?? omi/firmware/omi/" $3
            }
        ' "$profile_manifest" | LC_ALL=C sort)
        expected_source_status=$(printf '%s\n%s' \
            ' M omi/firmware/omi/CMakeLists.txt' \
            "$functional_untracked")
        ;;
    functional-recording-0002)
        functional_profile=true
        expected_sw_revision='gumi-functional-recording-0002'
        profile_config_relative='omi/firmware/omi/gumi-functional-recording.conf'
        profile_manifest="$firmware_dir/gumi/zephyr/omi-v3012/functional-recording-0002.manifest"
        profile_manifest_hash='ad030f1df863f970e92ecdaf36da3178a5f5acabf37326ce801e13998723f0c5'
        functional_untracked=$(awk '
            $1 !~ /^#/ && NF == 3 {
                print "?? omi/firmware/omi/" $3
            }
        ' "$profile_manifest" | LC_ALL=C sort)
        expected_source_status=$(printf '%s\n%s' \
            ' M omi/firmware/omi/CMakeLists.txt' \
            "$functional_untracked")
        ;;
    functional-recording-0003)
        functional_profile=true
        expected_sw_revision='gumi-functional-recording-0003'
        profile_config_relative='omi/firmware/omi/gumi-functional-recording.conf'
        profile_manifest="$firmware_dir/gumi/zephyr/omi-v3012/functional-recording-0003.manifest"
        profile_manifest_hash='ed9f9964f3a5e160dd50b07ec35cdf88e8130b061f6a0c9bfaef5a7dfcea30ce'
        functional_untracked=$(awk '
            $1 !~ /^#/ && NF == 3 {
                print "?? omi/firmware/omi/" $3
            }
        ' "$profile_manifest" | LC_ALL=C sort)
        expected_source_status=$(printf '%s\n%s' \
            ' M omi/firmware/omi/CMakeLists.txt' \
            "$functional_untracked")
        ;;
    functional-recording-0004)
        functional_profile=true
        expected_sw_revision='gumi-functional-recording-0004'
        profile_config_relative='omi/firmware/omi/gumi-functional-recording.conf'
        profile_manifest="$firmware_dir/gumi/zephyr/omi-v3012/functional-recording-0004.manifest"
        profile_manifest_hash='c95379f70ad198d7d7d286463752d7f0021d30fa5d95f4f1f125441d8a0b969f'
        functional_untracked=$(awk '
            $1 !~ /^#/ && NF == 3 {
                print "?? omi/firmware/omi/" $3
            }
        ' "$profile_manifest" | LC_ALL=C sort)
        expected_source_status=$(printf '%s\n%s' \
            ' M omi/firmware/omi/CMakeLists.txt' \
            "$functional_untracked")
        ;;
    functional-recording-0005)
        functional_profile=true
        expected_sw_revision='gumi-functional-recording-0005'
        profile_config_relative='omi/firmware/omi/gumi-functional-recording.conf'
        profile_manifest="$firmware_dir/gumi/zephyr/omi-v3012/functional-recording-0005.manifest"
        profile_manifest_hash='daedba504bac5e7230ff279e0719cbb492bd9a6929126773649f8b944e814169'
        functional_untracked=$(awk '
            $1 !~ /^#/ && NF == 3 {
                print "?? omi/firmware/omi/" $3
            }
        ' "$profile_manifest" | LC_ALL=C sort)
        expected_source_status=$(printf '%s\n%s' \
            ' M omi/firmware/omi/CMakeLists.txt' \
            "$functional_untracked")
        ;;
    functional-recording-0006)
        functional_profile=true
        expected_sw_revision='gumi-functional-recording-0006'
        profile_config_relative='omi/firmware/omi/gumi-functional-recording.conf'
        profile_manifest="$firmware_dir/gumi/zephyr/omi-v3012/functional-recording-0006.manifest"
        profile_manifest_hash='ab61c467acb425b57b5eefad91df9b9767eade4bad98257d41e313fefc7a9425'
        functional_untracked=$(awk '
            $1 !~ /^#/ && NF == 3 {
                print "?? omi/firmware/omi/" $3
            }
        ' "$profile_manifest" | LC_ALL=C sort)
        expected_source_status=$(printf '%s\n%s' \
            ' M omi/firmware/omi/CMakeLists.txt' \
            "$functional_untracked")
        ;;
    functional-recording-0007)
        functional_profile=true
        expected_sw_revision='gumi-functional-recording-0007'
        profile_config_relative='omi/firmware/omi/gumi-functional-recording.conf'
        profile_manifest="$firmware_dir/gumi/zephyr/omi-v3012/functional-recording-0007.manifest"
        profile_manifest_hash='0ea31fcdffe16b588e3382809b5ad93f9c648807529c9662ad14e90132a38316'
        functional_untracked=$(awk '
            $1 !~ /^#/ && NF == 3 {
                print "?? omi/firmware/omi/" $3
            }
        ' "$profile_manifest" | LC_ALL=C sort)
        expected_source_status=$(printf '%s\n%s' \
            ' M omi/firmware/omi/CMakeLists.txt' \
            "$functional_untracked")
        ;;
    recording-root-provisioner-0001)
        provisioner_profile=true
        expected_sw_revision='gumi-recording-root-provisioner-0001'
        profile_config_relative='omi/firmware/omi/gumi-recording-root-provisioner.conf'
        profile_manifest="$firmware_dir/gumi/zephyr/omi-v3012/recording-root-provisioner-0001.manifest"
        profile_manifest_hash='991dc25ff70746d6116a4d7afe9e266f2c7cb8619b8e5cf879401b5b47e1c9f6'
        provisioner_untracked=$(awk '
            $1 !~ /^#/ && NF == 3 {
                print "?? omi/firmware/omi/" $3
            }
        ' "$profile_manifest" | LC_ALL=C sort)
        expected_source_status=$(printf '%s\n%s' \
            ' M omi/firmware/omi/CMakeLists.txt' \
            "$provisioner_untracked")
        ;;
    legacy-storage-reclaimer-0001)
        reclaimer_profile=true
        expected_sw_revision='gumi-legacy-storage-reclaimer-0001'
        profile_config_relative='omi/firmware/omi/gumi-legacy-storage-reclaimer.conf'
        profile_manifest="$firmware_dir/gumi/zephyr/omi-v3012/legacy-storage-reclaimer-0001.manifest"
        profile_manifest_hash='3c36f64402f4cd0cb6e8b4175609e408f0c2ca94e74bf55c5929b0891540751e'
        reclaimer_untracked=$(awk '
            $1 !~ /^#/ && NF == 3 {
                print "?? omi/firmware/omi/" $3
            }
        ' "$profile_manifest" | LC_ALL=C sort)
        expected_source_status=$(printf '%s\n%s' \
            ' M omi/firmware/omi/CMakeLists.txt' \
            "$reclaimer_untracked")
        ;;
    legacy-storage-reclaimer-0002)
        reclaimer_profile=true
        expected_sw_revision='gumi-legacy-storage-reclaimer-0002'
        profile_config_relative='omi/firmware/omi/gumi-legacy-storage-reclaimer.conf'
        profile_manifest="$firmware_dir/gumi/zephyr/omi-v3012/legacy-storage-reclaimer-0002.manifest"
        profile_manifest_hash='b3f4cdf0028a29b32b53f8e9a6e05428090af6d679411104373ca884ef4f337c'
        reclaimer_untracked=$(awk '
            $1 !~ /^#/ && NF == 3 {
                print "?? omi/firmware/omi/" $3
            }
        ' "$profile_manifest" | LC_ALL=C sort)
        expected_source_status=$(printf '%s\n%s' \
            ' M omi/firmware/omi/CMakeLists.txt' \
            "$reclaimer_untracked")
        ;;
    kernel-link-probe-0001)
        expected_sw_revision='gumi-kernel-link-probe-0001'
        expected_source_status=' M omi/firmware/omi/CMakeLists.txt
 M omi/firmware/omi/omi.conf
?? omi/firmware/omi/src/gumi/button.c
?? omi/firmware/omi/src/gumi/capture.c
?? omi/firmware/omi/src/gumi/feedback.c
?? omi/firmware/omi/src/gumi/include/gumi/button.h
?? omi/firmware/omi/src/gumi/include/gumi/capture.h
?? omi/firmware/omi/src/gumi/include/gumi/feedback.h'
        ;;
    mic-port-link-probe-0001)
        expected_sw_revision='gumi-mic-port-probe-0001'
        expected_source_status=' M omi/firmware/omi/CMakeLists.txt
 M omi/firmware/omi/omi.conf
?? omi/firmware/omi/src/gumi/button.c
?? omi/firmware/omi/src/gumi/capture.c
?? omi/firmware/omi/src/gumi/feedback.c
?? omi/firmware/omi/src/gumi/include/gumi/button.h
?? omi/firmware/omi/src/gumi/include/gumi/capture.h
?? omi/firmware/omi/src/gumi/include/gumi/feedback.h
?? omi/firmware/omi/src/gumi/include/gumi/omi_v3012_mic.h
?? omi/firmware/omi/src/gumi/zephyr/omi-v3012/mic_port.c'
        ;;
    codec-port-link-probe-0001)
        expected_sw_revision='gumi-codec-port-probe-0001'
        expected_source_status=' M omi/firmware/omi/CMakeLists.txt
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
        ;;
    *)
        echo "unknown firmware build profile: $profile" >&2
        exit 64
        ;;
esac
actual_source_status=$(git -C "$source_repo" status --short --untracked-files=all -- omi/firmware)
[ "$actual_source_status" = "$expected_source_status" ] || {
    echo "materialized source contains changes outside the exact $profile overlay" >&2
    exit 1
}
git -C "$source_repo" diff --check -- omi/firmware
if [ "$profile" = canary-0001 ]; then
    shasum -a 256 "$source_repo/omi/firmware/omi/omi.conf" | \
        grep '^68b75bb56d703bf31c1dd050a96c4b14c0b5e9afb189a9df0ecd6554f770b095  ' >/dev/null
    shasum -a 256 "$source_repo/omi/firmware/omi/src/main.c" | \
        grep '^90c6bf1250e41b165c0146d50f63ee0a5d4fb02287962f901c28df2729dc2732  ' >/dev/null
    grep -F 'Gumi canary identity only' "$source_repo/omi/firmware/omi/src/main.c" >/dev/null
elif [ "$profile" = recovery-only-0001 ]; then
    shasum -a 256 "$source_repo/omi/firmware/omi/CMakeLists.txt" | \
        grep '^a343fb99e0630b8b0e5f223ee82c6da8206c501cc2e1bf878c09a06b8870f777  ' >/dev/null
    shasum -a 256 "$source_repo/$profile_config_relative" | \
        grep '^296fde8971be8c7d98878265d9783f77771e83bcdd4eb95bb89da75c1c4b394d  ' >/dev/null
    shasum -a 256 "$source_repo/omi/firmware/omi/src/gumi/include/gumi/recovery.h" | \
        grep '^d29f6c3b261e321c205b3be78b4b51e01d3e534dac62a5dc84b41a0258aa3780  ' >/dev/null
    shasum -a 256 "$source_repo/omi/firmware/omi/src/gumi/recovery.c" | \
        grep '^875ccf5d7ef9e7a75b48d0f9422c93198c250a36273e47558182919d2b6fa9e5  ' >/dev/null
    shasum -a 256 \
        "$source_repo/omi/firmware/omi/src/gumi/include/gumi/omi_v3012_mic.h" | \
        grep '^922b054be612aae87539c273da97eb3d4e2c00d616598cdb398176d03fd7a09b  ' >/dev/null
    shasum -a 256 \
        "$source_repo/omi/firmware/omi/src/gumi/include/gumi/omi_v3012_recovery.h" | \
        grep '^86f0c32fff18fdde29c6dc73f87bad60cf25e6f760b0ce57c242592c1a39c2a5  ' >/dev/null
    shasum -a 256 \
        "$source_repo/omi/firmware/omi/src/gumi/zephyr/omi-v3012/mic_port.c" | \
        grep '^0a1eb9f8e9748724f6b13071e19645803ef02730f3f93faa1fa21461fb70eafc  ' >/dev/null
    shasum -a 256 \
        "$source_repo/omi/firmware/omi/src/gumi/zephyr/omi-v3012/recovery_port.c" | \
        grep '^feb40341f5904fa047cb8081f67377c9b01e55922853aa51b88fe7729be9a851  ' >/dev/null
    shasum -a 256 \
        "$source_repo/omi/firmware/omi/src/gumi/zephyr/omi-v3012/recovery_only_main.c" | \
        grep '^90155497a28f2e6892bbe8596edc1de6319334cebe2486d93ee7b314781d317c  ' >/dev/null
elif [ "$profile" = capture-port-selftest-0001 ]; then
    shasum -a 256 "$source_repo/omi/firmware/omi/CMakeLists.txt" | \
        grep '^927978102f59dc3e973e6c49ce9d4698ec18f29c8a3c4dfccb667b1450bf8072  ' >/dev/null
    shasum -a 256 "$source_repo/$profile_config_relative" | \
        grep '^8cc9df7470b0785c13f50f0ec16010bb339146b1d8b5baadf5c01bdfad5026fe  ' >/dev/null
    shasum -a 256 "$source_repo/omi/firmware/omi/src/gumi/include/gumi/button.h" | \
        grep '^3d687a7aa02b652d4ea21d4269f36504df017fb6e4d9670a84039502b041c633  ' >/dev/null
    shasum -a 256 "$source_repo/omi/firmware/omi/src/gumi/include/gumi/capture_selftest.h" | \
        grep '^a763b5aa3dad3e3dd455938f5ebb62b249965877d77b69587c2a27161059b7b9  ' >/dev/null
    shasum -a 256 "$source_repo/omi/firmware/omi/src/gumi/button.c" | \
        grep '^61c389380dc16056a1bad9cb341bc488fd3ffebbfd675faba916014a4e939262  ' >/dev/null
    shasum -a 256 "$source_repo/omi/firmware/omi/src/gumi/capture_selftest.c" | \
        grep '^aa0836c0652bd0d2ab58197ba8b8fe726cdefda7ec0a863229635e9e508bf57f  ' >/dev/null
    shasum -a 256 "$source_repo/omi/firmware/omi/src/gumi/include/gumi/omi_v3012_capture_selftest.h" | \
        grep '^ccc5695a4a3664eb703bf467071da844fcb46f9f44fc8a2a101567685e4ce0cd  ' >/dev/null
    shasum -a 256 "$source_repo/omi/firmware/omi/src/gumi/include/gumi/omi_v3012_mic.h" | \
        grep '^922b054be612aae87539c273da97eb3d4e2c00d616598cdb398176d03fd7a09b  ' >/dev/null
    shasum -a 256 "$source_repo/omi/firmware/omi/src/gumi/include/gumi/omi_v3012_codec.h" | \
        grep '^364880210dcf280c1717bc179300f752b937f39516743ffeefad9e2d73c46aee  ' >/dev/null
    shasum -a 256 "$source_repo/omi/firmware/omi/src/gumi/zephyr/omi-v3012/mic_port.c" | \
        grep '^0a1eb9f8e9748724f6b13071e19645803ef02730f3f93faa1fa21461fb70eafc  ' >/dev/null
    shasum -a 256 "$source_repo/omi/firmware/omi/src/gumi/zephyr/omi-v3012/codec_port.c" | \
        grep '^e9df0036d3efd9ed771311b24da30c9647b06e1011b70c7cb75af67e43d8fa20  ' >/dev/null
    shasum -a 256 "$source_repo/omi/firmware/omi/src/gumi/zephyr/omi-v3012/capture_selftest_io.c" | \
        grep '^782155908ff5ad38eb87f4b23e94f4edc891dd7f5df360cfc061fd2e1d52c69e  ' >/dev/null
    shasum -a 256 "$source_repo/omi/firmware/omi/src/gumi/zephyr/omi-v3012/capture_selftest_transport.c" | \
        grep '^b9e72ccf921b3940e3d8f608fec81ca0fb61ac008f98f27f647d341e7d0e4196  ' >/dev/null
    shasum -a 256 "$source_repo/omi/firmware/omi/src/gumi/zephyr/omi-v3012/capture_selftest_main.c" | \
        grep '^851e65e475a5030087173be1de1f8fa3ad9ceee2cf30c3b91f027bb7394f7435  ' >/dev/null
elif [ "$functional_profile" = true ] || \
     [ "$provisioner_profile" = true ] || \
     [ "$reclaimer_profile" = true ]; then
    actual_manifest_hash=$(shasum -a 256 "$profile_manifest" | awk '{ print $1 }')
    [ "$actual_manifest_hash" = "$profile_manifest_hash" ] || {
        echo "$profile build manifest does not match its pinned hash" >&2
        exit 1
    }
    if [ "$functional_profile" = true ]; then
        grep -F 'Gumi functional stage: the stock application graph is not linked.' \
            "$source_repo/omi/firmware/omi/CMakeLists.txt" >/dev/null
    elif [ "$provisioner_profile" = true ]; then
        grep -F 'One-shot recording-root provisioner: no stock functional source is linked.' \
            "$source_repo/omi/firmware/omi/CMakeLists.txt" >/dev/null
    else
        grep -F 'One-purpose legacy archive reclaimer: no stock functional source is linked.' \
            "$source_repo/omi/firmware/omi/CMakeLists.txt" >/dev/null
    fi
    while read -r expected_hash _source_relative destination_relative; do
        case "$expected_hash" in
            '' | \#*)
                continue
                ;;
        esac
        actual_hash=$(shasum -a 256 \
            "$source_repo/omi/firmware/omi/$destination_relative" | \
            awk '{ print $1 }')
        [ "$actual_hash" = "$expected_hash" ] || {
            echo "materialized $profile source mismatch: $destination_relative" >&2
            exit 1
        }
    done < "$profile_manifest"
else
    shasum -a 256 "$source_repo/omi/firmware/omi/src/gumi/include/gumi/button.h" | \
        grep '^3d687a7aa02b652d4ea21d4269f36504df017fb6e4d9670a84039502b041c633  ' >/dev/null
    shasum -a 256 "$source_repo/omi/firmware/omi/src/gumi/include/gumi/capture.h" | \
        grep '^a703d085133c886fb46679451f743d1353431bb42548d9d03e09002bdbed7dfd  ' >/dev/null
    shasum -a 256 "$source_repo/omi/firmware/omi/src/gumi/include/gumi/feedback.h" | \
        grep '^c79b7f224d7651c1b92a46dd321f11c2cf20707302f7fe990a0ffa8687ce7e98  ' >/dev/null
    shasum -a 256 "$source_repo/omi/firmware/omi/src/gumi/button.c" | \
        grep '^61c389380dc16056a1bad9cb341bc488fd3ffebbfd675faba916014a4e939262  ' >/dev/null
    shasum -a 256 "$source_repo/omi/firmware/omi/src/gumi/capture.c" | \
        grep '^625d9ea850575886000fa7f2138e079962324820be6a966ed10624d0439f5a2a  ' >/dev/null
    shasum -a 256 "$source_repo/omi/firmware/omi/src/gumi/feedback.c" | \
        grep '^f0fffc6d20a658d377150f1e55fe63f2f5c9d5b3e7dcd81f11b1936610b66f0c  ' >/dev/null
    if [ "$profile" = kernel-link-probe-0001 ]; then
        shasum -a 256 "$source_repo/omi/firmware/omi/CMakeLists.txt" | \
            grep '^a47fb0ff45363f65573b1da8994a7320d3571a7ddea5ff3735e855b054153e66  ' >/dev/null
        shasum -a 256 "$source_repo/omi/firmware/omi/omi.conf" | \
            grep '^aadfdeee6f376255693d8cf2c68e0459fa5b9a0311df3bed3dfc46cf7c31586e  ' >/dev/null
    elif [ "$profile" = mic-port-link-probe-0001 ]; then
        shasum -a 256 "$source_repo/omi/firmware/omi/CMakeLists.txt" | \
            grep '^1facfeaefc365a10e7bc1d57129eeae86fd21924f02cd17ea2e7896d5fb24249  ' >/dev/null
        shasum -a 256 "$source_repo/omi/firmware/omi/omi.conf" | \
            grep '^4981597a50cbe1fa85f1afd2f966a352d3f03e4687478c408c579396b21924c0  ' >/dev/null
        shasum -a 256 \
            "$source_repo/omi/firmware/omi/src/gumi/include/gumi/omi_v3012_mic.h" | \
            grep '^922b054be612aae87539c273da97eb3d4e2c00d616598cdb398176d03fd7a09b  ' >/dev/null
        shasum -a 256 \
            "$source_repo/omi/firmware/omi/src/gumi/zephyr/omi-v3012/mic_port.c" | \
            grep '^0a1eb9f8e9748724f6b13071e19645803ef02730f3f93faa1fa21461fb70eafc  ' >/dev/null
    else
        shasum -a 256 "$source_repo/omi/firmware/omi/CMakeLists.txt" | \
            grep '^f1be6de1ae45329d21eb07caab68be0cf0b1930e7bafb67b357c2209d9fb7112  ' >/dev/null
        shasum -a 256 "$source_repo/omi/firmware/omi/omi.conf" | \
            grep '^2ca433e204cf4293a2d7eeab77bd7a4058020b0313190c6909a35ba9f916609f  ' >/dev/null
        shasum -a 256 \
            "$source_repo/omi/firmware/omi/src/gumi/include/gumi/omi_v3012_mic.h" | \
            grep '^922b054be612aae87539c273da97eb3d4e2c00d616598cdb398176d03fd7a09b  ' >/dev/null
        shasum -a 256 \
            "$source_repo/omi/firmware/omi/src/gumi/zephyr/omi-v3012/mic_port.c" | \
            grep '^0a1eb9f8e9748724f6b13071e19645803ef02730f3f93faa1fa21461fb70eafc  ' >/dev/null
        shasum -a 256 \
            "$source_repo/omi/firmware/omi/src/gumi/include/gumi/omi_v3012_codec.h" | \
            grep '^364880210dcf280c1717bc179300f752b937f39516743ffeefad9e2d73c46aee  ' >/dev/null
        shasum -a 256 \
            "$source_repo/omi/firmware/omi/src/gumi/zephyr/omi-v3012/codec_port.c" | \
            grep '^e9df0036d3efd9ed771311b24da30c9647b06e1011b70c7cb75af67e43d8fa20  ' >/dev/null
    fi
fi
shasum -a 256 "$source_repo/omi/firmware/bootloader/mcuboot/root-rsa-2048.pem" | \
    grep '^1fc912d30251b821f251e127d4daf7ba9338dd5c04e5af100abfb5b7c7d4c022  ' >/dev/null
grep -F "CONFIG_BT_DIS_SW_REV_STR=\"$expected_sw_revision\"" \
    "$source_repo/$profile_config_relative" >/dev/null

[ -d "$ncs_workspace/.west" ] || {
    echo "NCS workspace has no .west metadata: $ncs_workspace" >&2
    exit 1
}
[ "$(git -C "$ncs_workspace/nrf" rev-parse HEAD)" = 7787b264984022cda64d9629278942053e6462a5 ]
[ "$(git -C "$ncs_workspace/zephyr" rev-parse HEAD)" = 1f8f3dc291420c70cd39e77a5cdc954561d4a08f ]
[ "$(git -C "$ncs_workspace/bootloader/mcuboot" rev-parse HEAD)" = 12e5ee106034972b0f1074d6f2261b2b39d1501b ]
[ ! -e "$ncs_workspace/$build_name" ] || {
    echo "build output already exists: $ncs_workspace/$build_name" >&2
    exit 1
}

docker run --rm --pull never --network none --platform linux/amd64 \
    --mount "type=bind,src=$source_repo,dst=/omi/source,readonly" \
    --mount "type=bind,src=$ncs_workspace,dst=/ncs" \
    --workdir /ncs \
    "$toolchain_image" \
    "set -eu
git config --global --add safe.directory '*'
west zephyr-export
cp '/omi/source/$profile_config_relative' '/ncs/gumi-$profile-prj.conf'
west build -b omi/nrf5340/cpuapp /omi/source/omi/firmware/omi --sysbuild \\
  -d '$build_name' --pristine always -- \\
  -DBOARD_ROOT=/omi/source/omi/firmware \\
  -DCONF_FILE='/ncs/gumi-$profile-prj.conf'
artifact_wait=0
while [ ! -s '$build_name/omi/zephyr/zephyr.signed.bin' ] && [ \"\$artifact_wait\" -lt 10 ]; do
  artifact_wait=\$((artifact_wait + 1))
  sleep 1
done
test -s '$build_name/omi/zephyr/zephyr.signed.bin'
echo 'verified=signed_application'
if [ '$profile' = kernel-link-probe-0001 ] || \
   [ '$profile' = mic-port-link-probe-0001 ] || \
   [ '$profile' = codec-port-link-probe-0001 ]; then
  test -s '$build_name/omi/CMakeFiles/app.dir/src/gumi/button.c.obj'
  test -s '$build_name/omi/CMakeFiles/app.dir/src/gumi/capture.c.obj'
  test -s '$build_name/omi/CMakeFiles/app.dir/src/gumi/feedback.c.obj'
  echo 'verified=gumi_kernel_objects'
fi
if [ '$profile' = mic-port-link-probe-0001 ] || \
   [ '$profile' = codec-port-link-probe-0001 ]; then
  test -s '$build_name/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/mic_port.c.obj'
  echo 'verified=gumi_mic_port_object'
fi
if [ '$profile' = codec-port-link-probe-0001 ]; then
  test -s '$build_name/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/codec_port.c.obj'
  echo 'verified=gumi_codec_port_object'
fi
if [ '$profile' = recovery-only-0001 ]; then
  test -s '$build_name/omi/CMakeFiles/app.dir/src/gumi/recovery.c.obj'
  test -s '$build_name/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/mic_port.c.obj'
  test -s '$build_name/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/recovery_port.c.obj'
  test -s '$build_name/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/recovery_only_main.c.obj'
  echo 'verified=gumi_recovery_only_objects'
fi
if [ '$profile' = capture-port-selftest-0001 ]; then
  test -s '$build_name/omi/CMakeFiles/app.dir/src/gumi/button.c.obj'
  test -s '$build_name/omi/CMakeFiles/app.dir/src/gumi/capture_selftest.c.obj'
  test -s '$build_name/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/mic_port.c.obj'
  test -s '$build_name/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/codec_port.c.obj'
  test -s '$build_name/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/capture_selftest_io.c.obj'
  test -s '$build_name/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/capture_selftest_transport.c.obj'
  test -s '$build_name/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/capture_selftest_main.c.obj'
  echo 'verified=gumi_capture_port_selftest_objects'
fi
if [ '$functional_profile' = true ]; then
  test -s '$build_name/omi/CMakeFiles/app.dir/src/gumi/capture.c.obj'
  test -s '$build_name/omi/CMakeFiles/app.dir/src/gumi/recording_journal.c.obj'
  test -s '$build_name/omi/CMakeFiles/app.dir/src/gumi/recording_store.c.obj'
  test -s '$build_name/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/codec_port.c.obj'
  test -s '$build_name/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/functional_main.c.obj'
  test -s '$build_name/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/functional_transport.c.obj'
  test -s '$build_name/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/recording_key_port.c.obj'
  if [ '$profile' = functional-recording-0007 ]; then
    test -s '$build_name/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/recording_storage_port_v0007.c.obj'
  else
    test -s '$build_name/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/recording_storage_port.c.obj'
  fi
  if [ '$profile' = functional-recording-0006 ] || \
     [ '$profile' = functional-recording-0007 ]; then
    test -s '$build_name/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/functional_reset_port.c.obj'
  fi
  echo 'verified=gumi_functional_recording_objects'
fi
if [ '$provisioner_profile' = true ]; then
  test -s '$build_name/omi/CMakeFiles/app.dir/src/gumi/recovery.c.obj'
  test -s '$build_name/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/mic_port.c.obj'
  test -s '$build_name/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/recovery_port.c.obj'
  test -s '$build_name/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/recording_root_provisioner_port.c.obj'
  test -s '$build_name/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/recording_root_provisioner_main.c.obj'
  echo 'verified=gumi_recording_root_provisioner_objects'
fi
if [ '$reclaimer_profile' = true ]; then
  test -s '$build_name/omi/CMakeFiles/app.dir/src/gumi/legacy_storage_reclaimer.c.obj'
  test -s '$build_name/omi/CMakeFiles/app.dir/src/gumi/recovery.c.obj'
  test -s '$build_name/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/functional_reset_port.c.obj'
  test -s '$build_name/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/legacy_storage_reclaimer_main.c.obj'
  test -s '$build_name/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/legacy_storage_reclaimer_port.c.obj'
  test -s '$build_name/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/legacy_storage_reclaimer_transport.c.obj'
  test -s '$build_name/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/mic_port.c.obj'
  test -s '$build_name/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/recovery_port.c.obj'
  if grep -F 'fs_mkfs' '$build_name/omi/zephyr/zephyr.map' >/dev/null; then
    echo 'forbidden filesystem formatter reached the reclaimer link' >&2
    exit 1
  fi
  echo 'verified=gumi_legacy_storage_reclaimer_objects'
fi
echo 'build_result=pass'"

docker run --rm --pull never --network none --platform linux/amd64 \
    --env PYTHONDONTWRITEBYTECODE=1 \
    --mount "type=bind,src=$source_repo,dst=/omi/source,readonly" \
    --mount "type=bind,src=$ncs_workspace,dst=/ncs,readonly" \
    --mount "type=bind,src=$firmware_dir,dst=/gumi-firmware,readonly" \
    --workdir /ncs \
    "$toolchain_image" \
    "sh /gumi-firmware/scripts/verify-build-output.sh \\
      '/ncs/$build_name' /omi/source '$profile'"

application="$ncs_workspace/$build_name/omi/zephyr/zephyr.signed.bin"
[ -s "$application" ] || {
    echo "container reported success but the host application artifact is missing" >&2
    exit 1
}
echo "application=$application"
echo "profile=$profile"
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
echo "warning=network and complete OTA artifacts are quarantined and must not be uploaded"
