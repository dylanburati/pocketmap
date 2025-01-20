use libproc::libproc::file_info::ListFDs;
use libproc::libproc::pid_rusage::{pidrusage, RUsageInfoV2};
use libproc::libproc::proc_pid::{listpidinfo, pidinfo};
use libproc::libproc::task_info::TaskAllInfo;
use libproc::libproc::thread_info::ThreadInfo;
use libproc::proc_pid::ListThreads;
use mach2::mach_time;
use once_cell::sync::Lazy;
use rlimit::{getrlimit, Resource};
use std::mem::MaybeUninit;

use super::types::Metrics;

// https://stackoverflow.com/a/72915413
// https://openradar.appspot.com/FB9546856
// https://developer.apple.com/documentation/kernel/mach_timebase_info_data_t
static TIMEBASE_TO_NANOSECONDS: Lazy<f64> = Lazy::new(|| {
    let mut info = MaybeUninit::uninit();
    let info = unsafe {
        mach_time::mach_timebase_info(info.as_mut_ptr());
        info.assume_init()
    };
    info.numer as f64 / info.denom as f64
});

// static BOOTTIME: Lazy<f64> = Lazy::new(|| {
//     let mut args = [CTL_KERN, KERN_BOOTTIME];
//     let mut res: MaybeUninit<timeval> = MaybeUninit::uninit();
//     let mut sz = std::mem::size_of::<timeval>();
//     let tv = unsafe {
//         match libc::sysctl(args.as_mut_ptr(), args.len() as u32, std::mem::transmute(res.as_mut_ptr()), &mut sz, std::ptr::null_mut(), 0) {
//             0 => res.assume_init(),
//             _ => panic!("failed to get boot time"),
//         }
//     };
//     tv.tv_sec as f64 + 1e-6 * tv.tv_usec as f64
// });

pub fn first_child(_pid: i32) -> Result<Option<i32>, ()> {
    Err(())
}

pub fn collect(pid: i32) -> Metrics {
    let mut metrics = Metrics::default();
    if let Ok(res) = pidrusage::<RUsageInfoV2>(pid) {
        metrics.cpu_seconds_total = {
            let t = res.ri_user_time + res.ri_system_time;
            let t = t as f64 * *TIMEBASE_TO_NANOSECONDS / 1e9;
            Some(t)
        };
    }
    metrics.time_seconds = {
        let mut res = MaybeUninit::uninit();
        let tv = unsafe {
            match libc::gettimeofday(res.as_mut_ptr(), std::ptr::null_mut()) {
                0 => res.assume_init(),
                _ => panic!("failed to get time"),
            }
        };
        Some(tv.tv_sec as f64 + 1e-6 * tv.tv_usec as f64)
    };
    if let Ok(info) = pidinfo::<TaskAllInfo>(pid, 0) {
        if let Ok(threads) = listpidinfo::<ListThreads>(pid, info.ptinfo.pti_threadnum as usize) {
            metrics.start_time_seconds = Some(info.pbsd.pbi_start_tvsec as f64 + 1e-6 * info.pbsd.pbi_start_tvusec as f64);
            metrics.virtual_memory_bytes = Some(info.ptinfo.pti_virtual_size);
            metrics.resident_memory_bytes = Some(info.ptinfo.pti_resident_size);
            metrics.num_threads = Some(info.ptinfo.pti_threadnum as u64);
            metrics.open_fds = listpidinfo::<ListFDs>(pid, info.pbsd.pbi_nfiles as usize)
                .ok()
                .map(|v| v.len() as u64);
            metrics.thread_cpu_seconds_total = threads
                .into_iter()
                .map(|tid| pidinfo::<ThreadInfo>(pid, tid).map(|tinfo| {
                    let t = tinfo.pth_user_time + tinfo.pth_system_time;
                    let t = t as f64 * *TIMEBASE_TO_NANOSECONDS / 1e9;
                    ((tid & 0x7fff_ffff) as i32, t)
                }))
                .collect::<Result<Vec<_>, _>>()
                .ok()
        }
    }
    metrics.virtual_memory_max_bytes = getrlimit(Resource::AS).ok().map(|(soft, _hard)| soft);
    metrics.max_fds = getrlimit(Resource::NOFILE).ok().map(|(soft, _hard)| soft);
    metrics
}
