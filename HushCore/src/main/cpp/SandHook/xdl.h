// Copyright (c) 2020-2023 HexHacking Team
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.
//

// Created by caikelun on 2020-10-04.

//
// xDL version: 2.1.1
//
// xDL is an enhanced implementation of the Android DL series functions.
// For more information, documentation, and the latest version please check:
// https://github.com/hexhacking/xDL
//

#ifndef IO_GITHUB_HEXHACKING_XDL
#define IO_GITHUB_HEXHACKING_XDL

#include <dlfcn.h>
#include <link.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
  // same as Dl_info:
  const char *dli_fname;  // Pathname of shared object that contains address.
  void *dli_fbase;        // Address at which shared object is loaded.
  const char *dli_sname;  // Name of nearest symbol with address lower than addr.
  void *dli_saddr;        // Exact address of symbol named in dli_sname.
  // added by xDL:
  size_t dli_ssize;             // Symbol size of nearest symbol with address lower than addr.
  const ElfW(Phdr) *dlpi_phdr;  // Pointer to array of ELF program headers for this object.
  size_t dlpi_phnum;            // Number of items in dlpi_phdr.
} xdl_info_t;

//
// Default value for flags in both xdl_open() and xdl_iterate_phdr().
//
#define XDL_DEFAULT 0x00

//
// Enhanced dlopen() / dlclose() / dlsym().
//
#define XDL_TRY_FORCE_LOAD    0x01
#define XDL_ALWAYS_FORCE_LOAD 0x02
void *xdl_open(const char *filename, int flags);
void *xdl_close(void *handle);
void *xdl_sym(void *handle, const char *symbol, size_t *symbol_size);
void *xdl_dsym(void *handle, const char *symbol, size_t *symbol_size);

/*
 * 找出第一个「以 prefix 开头」的导出符号。
 * ART 里有一部分符号会因为 LTO / 链接方式带后缀（例如 ...GetOptimizedCodeForE 与
 * ...GetOptimizedCodeForE.cold、以及 L 链接变体），精确名字查不到时只能按前缀找。
 */
void *xdl_sym_prefix(void *handle, const char *prefix, size_t *symbol_size);

/*
 * 把 handle 对应模块的 dynsym 统计写进 out（给日志用）：
 * 模块路径、load bias、dynsym 条目数、.gnu.hash / .hash 的桶数与 symoffset。
 * 返回 snprintf 的长度，参数非法返回 -1。
 */
int xdl_diag(void *handle, char *out, size_t out_sz);

/*
 * 把 dynsym 里名字包含 substr 的导出符号拼进 out（空格分隔，最多 max_count 个），
 * 返回命中数量。用于「符号名对不上」时直接看 ART 里到底叫什么。
 */
int xdl_list_syms(void *handle, const char *substr, char *out, size_t out_sz, int max_count);

//
// Enhanced dladdr().
//
int xdl_addr(void *addr, xdl_info_t *info, void **cache);
void xdl_addr_clean(void **cache);

//
// Enhanced dl_iterate_phdr().
//
#define XDL_FULL_PATHNAME 0x01
int xdl_iterate_phdr(int (*callback)(struct dl_phdr_info *, size_t, void *), void *data, int flags);

//
// Custom dlinfo().
//
#define XDL_DI_DLINFO 1  // type of info: xdl_info_t
int xdl_info(void *handle, int request, void *info);

#ifdef __cplusplus
}
#endif

#endif
