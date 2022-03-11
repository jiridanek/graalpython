/* MIT License
 *
 * Copyright (c) 2020, 2021, Oracle and/or its affiliates.
 * Copyright (c) 2019 pyhandle
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

#ifndef HPY_UNIVERSAL_HPYFUNC_TRAMPOLINES_H
#define HPY_UNIVERSAL_HPYFUNC_TRAMPOLINES_H

/* This file should be autogenerated */

typedef struct {
    cpy_PyObject *self;
    cpy_PyObject *result;
} _HPyFunc_args_NOARGS;

typedef struct {
    cpy_PyObject *self;
    cpy_PyObject *arg;
    cpy_PyObject *result;
} _HPyFunc_args_O;

typedef struct {
    cpy_PyObject *self;
    cpy_PyObject *args;
    cpy_PyObject *result;
} _HPyFunc_args_VARARGS;

typedef struct {
    cpy_PyObject *self;
    cpy_PyObject *args;
    cpy_PyObject *kw;
    cpy_PyObject *result;
} _HPyFunc_args_KEYWORDS;

typedef struct {
    cpy_PyObject *self;
    cpy_PyObject *args;
    cpy_PyObject *kw;
    int result;
} _HPyFunc_args_INITPROC;

typedef struct {
    cpy_PyObject *arg0;
    cpy_PyObject *arg1;
    HPy_RichCmpOp arg2;
    cpy_PyObject * result;
} _HPyFunc_args_RICHCMPFUNC;


#define _HPyFunc_TRAMPOLINE_HPyFunc_NOARGS(SYM, IMPL)                   \
    static cpy_PyObject *                                               \
    SYM(cpy_PyObject *self, cpy_PyObject *noargs)                       \
    {                                                                   \
        _HPyFunc_args_NOARGS a = { self };                              \
        _HPy_CallRealFunctionFromTrampoline(                            \
            _ctx_for_trampolines, HPyFunc_NOARGS, (HPyCFunction)IMPL, &a);            \
        return a.result;                                                \
    }

#define _HPyFunc_TRAMPOLINE_HPyFunc_O(SYM, IMPL)                        \
    static cpy_PyObject *                                               \
    SYM(cpy_PyObject *self, cpy_PyObject *arg)                          \
    {                                                                   \
        _HPyFunc_args_O a = { self, arg };                              \
        _HPy_CallRealFunctionFromTrampoline(                            \
            _ctx_for_trampolines, HPyFunc_O, (HPyCFunction)IMPL, &a);                 \
        return a.result;                                                \
    }


#define _HPyFunc_TRAMPOLINE_HPyFunc_VARARGS(SYM, IMPL)                  \
    static cpy_PyObject *                                               \
    SYM(cpy_PyObject *self, cpy_PyObject *args)                         \
    {                                                                   \
        _HPyFunc_args_VARARGS a = { self, args };                       \
        _HPy_CallRealFunctionFromTrampoline(                            \
            _ctx_for_trampolines, HPyFunc_VARARGS, (HPyCFunction)IMPL, &a);           \
        return a.result;                                                \
    }


#define _HPyFunc_TRAMPOLINE_HPyFunc_KEYWORDS(SYM, IMPL)                 \
    static cpy_PyObject *                                               \
    SYM(cpy_PyObject *self, cpy_PyObject *args, cpy_PyObject *kw)       \
    {                                                                   \
        _HPyFunc_args_KEYWORDS a = { self, args, kw };                  \
        _HPy_CallRealFunctionFromTrampoline(                            \
            _ctx_for_trampolines, HPyFunc_KEYWORDS, (HPyCFunction)IMPL, &a);          \
        return a.result;                                                \
    }

#define _HPyFunc_TRAMPOLINE_HPyFunc_INITPROC(SYM, IMPL)                 \
    static int                                                          \
    SYM(cpy_PyObject *self, cpy_PyObject *args, cpy_PyObject *kw)       \
    {                                                                   \
        _HPyFunc_args_INITPROC a = { self, args, kw };                  \
        _HPy_CallRealFunctionFromTrampoline(                            \
            _ctx_for_trampolines, HPyFunc_INITPROC, (HPyCFunction)IMPL, &a);          \
        return a.result;                                                \
    }

/* special case: the HPy_tp_destroy slot doesn't map to any CPython slot.
   Instead, it is called from our own tp_dealloc: see also
   hpytype_dealloc(). */
#define _HPyFunc_TRAMPOLINE_HPyFunc_DESTROYFUNC(SYM, IMPL)              \
    static void SYM(void) { abort(); }


/* this needs to be written manually because HPy has a different type for
   "op": HPy_RichCmpOp instead of int */
#define _HPyFunc_TRAMPOLINE_HPyFunc_RICHCMPFUNC(SYM, IMPL)              \
    static cpy_PyObject *                                               \
    SYM(cpy_PyObject *self, cpy_PyObject *obj, int op)                  \
    {                                                                   \
        _HPyFunc_args_RICHCMPFUNC a = { self, obj, (HPy_RichCmpOp)op };                \
        _HPy_CallRealFunctionFromTrampoline(                            \
           _ctx_for_trampolines, HPyFunc_RICHCMPFUNC, (HPyCFunction)IMPL, &a);        \
        return a.result;                                                \
    }

typedef struct {
    cpy_PyObject *self;
    cpy_Py_buffer *view;
    int flags;
    int result;
} _HPyFunc_args_GETBUFFERPROC;

#define _HPyFunc_TRAMPOLINE_HPyFunc_GETBUFFERPROC(SYM, IMPL) \
    static int SYM(cpy_PyObject *self, cpy_Py_buffer *view, int flags) \
    { \
        _HPyFunc_args_GETBUFFERPROC a = {self, view, flags}; \
        _HPy_CallRealFunctionFromTrampoline( \
           _ctx_for_trampolines, HPyFunc_GETBUFFERPROC, (HPyCFunction)IMPL, &a); \
        return a.result; \
    }

typedef struct {
    cpy_PyObject *self;
    cpy_Py_buffer *view;
} _HPyFunc_args_RELEASEBUFFERPROC;

#define _HPyFunc_TRAMPOLINE_HPyFunc_RELEASEBUFFERPROC(SYM, IMPL) \
    static void SYM(cpy_PyObject *self, cpy_Py_buffer *view) \
    { \
        _HPyFunc_args_RELEASEBUFFERPROC a = {self, view}; \
        _HPy_CallRealFunctionFromTrampoline( \
           _ctx_for_trampolines, HPyFunc_RELEASEBUFFERPROC, (HPyCFunction)IMPL, &a); \
        return; \
    }


typedef struct {
    cpy_PyObject *self;
    cpy_visitproc visit;
    void *arg;
    int result;
} _HPyFunc_args_TRAVERSEPROC;

#define _HPyFunc_TRAMPOLINE_HPyFunc_TRAVERSEPROC(SYM, IMPL) \
    static int SYM(cpy_PyObject *self, cpy_visitproc visit, void *arg) \
    { \
        _HPyFunc_args_TRAVERSEPROC a = { self, visit, arg }; \
        _HPy_CallRealFunctionFromTrampoline( \
           _ctx_for_trampolines, HPyFunc_TRAVERSEPROC, (HPyCFunction)IMPL, &a); \
        return a.result; \
    }



#endif // HPY_UNIVERSAL_HPYFUNC_TRAMPOLINES_H
