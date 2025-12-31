#include <linux/compiler.h>
#include <linux/version.h>
#include <linux/slab.h>
#include <linux/task_work.h>
#include <linux/thread_info.h>
#include <linux/seccomp.h>
#include <linux/printk.h>
#include <linux/sched.h>
#if LINUX_VERSION_CODE >= KERNEL_VERSION(4, 10, 0)
#include <linux/sched/signal.h>
#endif
#include <linux/string.h>
#include <linux/types.h>
#include <linux/uaccess.h>
#include <linux/uidgid.h>

#ifdef CONFIG_KSU_SUSFS
#include <linux/susfs_def.h>
#endif // #ifdef CONFIG_KSU_SUSFS

#include "allowlist.h"
#include "setuid_hook.h"
#include "klog.h" // IWYU pragma: keep
#include "manager.h"
#include "selinux/selinux.h"
#include "seccomp_cache.h"
#include "supercalls.h"
#ifdef KSU_TP_HOOK
#include "syscall_hook_manager.h"
#endif
#include "kernel_compat.h"
#include "kernel_umount.h"
#include "sulog.h"

extern u32 ksu_zygote_sid;
#ifdef CONFIG_KSU_SUSFS
static inline bool is_zygote_isolated_service_uid(uid_t uid)
{
    uid %= 100000;
    return (uid >= 99000 && uid < 100000);
}

static inline bool is_zygote_normal_app_uid(uid_t uid)
{
    uid %= 100000;
    return (uid >= 10000 && uid < 19999);
}

#ifdef CONFIG_KSU_SUSFS_SUS_PATH
extern void susfs_run_sus_path_loop(uid_t uid);
#endif // #ifdef CONFIG_KSU_SUSFS_SUS_PATH
#ifdef CONFIG_KSU_SUSFS_SUS_MOUNT
extern void susfs_reorder_mnt_id(void);
#endif // #ifdef CONFIG_KSU_SUSFS_SUS_MOUNT
#endif // #ifdef CONFIG_KSU_SUSFS

extern void disable_seccomp(struct task_struct *tsk);

#ifndef CONFIG_KSU_SUSFS
int ksu_handle_setuid(uid_t new_uid, uid_t old_uid, uid_t euid) // (new_euid)
{
    // We only interest in process spwaned by zygote
    if (!ksu_is_sid_equal(current_cred(), ksu_zygote_sid)) {
        return 0;
    }

    if (old_uid != new_uid)
        pr_info("handle_setresuid from %d to %d\n", old_uid, new_uid);

    // if on private space, see if its possibly the manager
    if (new_uid > PER_USER_RANGE &&
        new_uid % PER_USER_RANGE == ksu_get_manager_uid()) {
        ksu_set_manager_uid(new_uid);
    }

#if LINUX_VERSION_CODE >= KERNEL_VERSION(5, 10, 0)
    if (ksu_get_manager_uid() == new_uid) {
        pr_info("install fd for ksu manager(uid=%d)\n", new_uid);
        ksu_install_fd();
        spin_lock_irq(&current->sighand->siglock);
        ksu_seccomp_allow_cache(current->seccomp.filter, __NR_reboot);
#ifdef KSU_TP_HOOK
        ksu_set_task_tracepoint_flag(current);
#endif
        spin_unlock_irq(&current->sighand->siglock);
        return 0;
    }

    if (ksu_is_allow_uid_for_current(new_uid)) {
        if (current->seccomp.mode == SECCOMP_MODE_FILTER &&
            current->seccomp.filter) {
            spin_lock_irq(&current->sighand->siglock);
            ksu_seccomp_allow_cache(current->seccomp.filter, __NR_reboot);
            spin_unlock_irq(&current->sighand->siglock);
        }
#ifdef KSU_TP_HOOK
        ksu_set_task_tracepoint_flag(current);
#endif
    }
#ifdef KSU_TP_HOOK
    else {
        ksu_clear_task_tracepoint_flag_if_needed(current);
    }
#endif

#else // #if LINUX_VERSION_CODE >= KERNEL_VERSION(5, 10, 0)
    if (ksu_is_allow_uid_for_current(new_uid)) {
        spin_lock_irq(&current->sighand->siglock);
        disable_seccomp(current);
        spin_unlock_irq(&current->sighand->siglock);

        if (ksu_get_manager_uid() == new_uid) {
            pr_info("install fd for ksu manager(uid=%d)\n", new_uid);
            ksu_install_fd();
        }

        return 0;
    }
#endif // #if LINUX_VERSION_CODE >= KERNEL_VERSION(5, 10, 0)

    // Handle kernel umount
    ksu_handle_umount(old_uid, new_uid);

    return 0;
}
#else
int ksu_handle_setuid(uid_t new_uid, uid_t old_uid, uid_t euid)
{
    // We only interest in process spwaned by zygote
    if (!ksu_is_sid_equal(current_cred(), ksu_zygote_sid)) {
        return 0;
    }

#if __SULOG_GATE
    ksu_sulog_report_syscall(new_uid, NULL, "setuid", NULL);
#endif

#ifdef CONFIG_KSU_SUSFS_SUS_MOUNT
    // Check if spawned process is isolated service first, and force to do umount if so
    if (is_zygote_isolated_service_uid(new_uid)) {
        goto do_umount;
    }
#endif // #ifdef CONFIG_KSU_SUSFS_SUS_MOUNT

    // - Since ksu maanger app uid is excluded in allow_list_arr, so ksu_uid_should_umount(manager_uid)
    //   will always return true, that's why we need to explicitly check if new_uid belongs to
    //   ksu manager
#if LINUX_VERSION_CODE >= KERNEL_VERSION(5, 10, 0)
    if (ksu_get_manager_uid() == new_uid) {
        pr_info("install fd for ksu manager(uid=%d)\n", new_uid);
        ksu_install_fd();
        spin_lock_irq(&current->sighand->siglock);
        ksu_seccomp_allow_cache(current->seccomp.filter, __NR_reboot);
        spin_unlock_irq(&current->sighand->siglock);
        return 0;
    }

    if (ksu_is_allow_uid_for_current(new_uid)) {
        if (current->seccomp.mode == SECCOMP_MODE_FILTER &&
            current->seccomp.filter) {
            spin_lock_irq(&current->sighand->siglock);
            ksu_seccomp_allow_cache(current->seccomp.filter, __NR_reboot);
            spin_unlock_irq(&current->sighand->siglock);
        }
    }

#else // #if LINUX_VERSION_CODE >= KERNEL_VERSION(5, 10, 0)
    if (ksu_is_allow_uid_for_current(new_uid)) {
        spin_lock_irq(&current->sighand->siglock);
        disable_seccomp(current);
        spin_unlock_irq(&current->sighand->siglock);

        if (ksu_get_manager_uid() == new_uid) {
            pr_info("install fd for ksu manager(uid=%d)\n", new_uid);
            ksu_install_fd();
        }

        return 0;
    }
#endif // #if LINUX_VERSION_CODE >= KERNEL_VERSION(5, 10, 0)

    // Check if spawned process is normal user app and needs to be umounted
    if (likely(is_zygote_normal_app_uid(new_uid) &&
               ksu_uid_should_umount(new_uid))) {
        goto do_umount;
    }

    return 0;

do_umount:
    // Handle kernel umount
    ksu_handle_umount(old_uid, new_uid);

#ifdef CONFIG_KSU_SUSFS_SUS_MOUNT
    // We can reorder the mnt_id now after all sus mounts are umounted
    susfs_reorder_mnt_id();
#endif // #ifdef CONFIG_KSU_SUSFS_SUS_MOUNT

#ifdef CONFIG_KSU_SUSFS_SUS_PATH
    susfs_run_sus_path_loop(new_uid);
#endif // #ifdef CONFIG_KSU_SUSFS_SUS_PATH

    susfs_set_current_proc_umounted();

    return 0;
}
#endif // #ifndef CONFIG_KSU_SUSFS

int ksu_handle_setresuid(uid_t ruid, uid_t euid, uid_t suid)
{
#ifdef CONFIG_KSU_MANUAL_HOOK_AUTO_SETUID_HOOK
    return 0; // dummy hook here
#else
    // we rely on the fact that zygote always call setresuid(3) with same uids
    return ksu_handle_setuid(ruid, current_uid().val, euid);
#endif
}

void ksu_setuid_hook_init(void)
{
    ksu_kernel_umount_init();
}

void ksu_setuid_hook_exit(void)
{
    pr_info("ksu_setuid_hook_exit\n");
    ksu_kernel_umount_exit();
}
