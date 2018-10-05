/*
 * Copyright (c) 2002, 2009, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

package com.jetbrains.sa.jdi;

import com.sun.jdi.*;
import com.jetbrains.sa.jdwp.JDWP;
import sun.jvm.hotspot.debugger.OopHandle;
import sun.jvm.hotspot.oops.Instance;
import sun.jvm.hotspot.oops.ObjectHeap;
import sun.jvm.hotspot.oops.Oop;
import sun.jvm.hotspot.oops.OopUtilities;
import sun.jvm.hotspot.runtime.MonitorInfo;
import sun.jvm.hotspot.runtime.*;
import sun.jvm.hotspot.utilities.Assert;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

//import sun.jvm.hotspot.runtime.StackFrameStream;

public class ThreadReferenceImpl extends ObjectReferenceImpl
             implements ThreadReference, /* imports */ JVMTIThreadState {

    private JavaThread myJavaThread;
    private ArrayList<StackFrame> frames;    // StackFrames
    private List<ObjectReference> ownedMonitors;
    private List<com.sun.jdi.MonitorInfo> ownedMonitorsInfo; // List<MonitorInfo>
    private ObjectReferenceImpl currentContendingMonitor;

    ThreadReferenceImpl(VirtualMachine aVm, JavaThread aRef) {
        // We are given a JavaThread and save it in our myJavaThread field.
        // But, our parent class is an ObjectReferenceImpl so we need an Oop
        // for it.  JavaThread is a wrapper around a Thread Oop so we get
        // that Oop and give it to our super.
        // We can get it back again by calling ref().
        super(aVm, aRef.getThreadObj());
        myJavaThread = aRef;
    }

    ThreadReferenceImpl(VirtualMachine vm, Instance oRef) {
        // Instance must be of type java.lang.Thread
        super(vm, oRef);

        // JavaThread retrieved from java.lang.Thread instance may be null.
        // This is the case for threads not-started and for zombies. Wherever
        // appropriate, check for null instead of resulting in NullPointerException.
        myJavaThread = OopUtilities.threadOopGetJavaThread(oRef);
    }

    // return value may be null. refer to the comment in constructor.
    JavaThread getJavaThread() {
        return myJavaThread;
    }

    protected String description() {
        return "ThreadReference " + uniqueID();
    }

    /**
     * Note that we only cache the name string while suspended because
     * it can change via Thread.setName arbitrarily
     */
    public String name() {
        return OopUtilities.threadOopGetName(ref());
    }

    public void suspend() {
        vm.throwNotReadOnlyException("ThreadReference.suspend()");
    }

    public void resume() {
        vm.throwNotReadOnlyException("ThreadReference.resume()");
    }

    public int suspendCount() {
        // all threads are "suspended" when we attach to process or core.
        // we interpret this as one suspend.
        return 1;
    }

    public void stop(ObjectReference throwable) {
        vm.throwNotReadOnlyException("ThreadReference.stop()");
    }

    public void interrupt() {
        vm.throwNotReadOnlyException("ThreadReference.interrupt()");
    }

    // refer to jvmtiEnv::GetThreadState
    private int jvmtiGetThreadState() {
        // get most state bits
        int state = OopUtilities.threadOopGetThreadStatus(ref());
        // add more state bits
        if (myJavaThread != null) {
            JavaThreadState jts = myJavaThread.getThreadState();
            if (myJavaThread.isBeingExtSuspended()) {
                state |= JVMTI_THREAD_STATE_SUSPENDED;
            }
            if (jts == JavaThreadState.IN_NATIVE) {
                state |= JVMTI_THREAD_STATE_IN_NATIVE;
            }
            OSThread osThread = myJavaThread.getOSThread();
            if (osThread != null && osThread.interrupted()) {
                state |= JVMTI_THREAD_STATE_INTERRUPTED;
            }
        }
        return state;
    }

    public int status() {
        int state = jvmtiGetThreadState();
        int status = THREAD_STATUS_UNKNOWN;
        // refer to map2jdwpThreadStatus in util.c (back-end)
        if (! ((state & JVMTI_THREAD_STATE_ALIVE) != 0) ) {
            if ((state & JVMTI_THREAD_STATE_TERMINATED) != 0) {
                status = THREAD_STATUS_ZOMBIE;
            } else {
                status = THREAD_STATUS_NOT_STARTED;
            }
        } else {
            if ((state & JVMTI_THREAD_STATE_SLEEPING) != 0) {
                status = THREAD_STATUS_SLEEPING;
            } else if ((state & JVMTI_THREAD_STATE_BLOCKED_ON_MONITOR_ENTER) != 0) {
                status = THREAD_STATUS_MONITOR;
            } else if ((state & JVMTI_THREAD_STATE_WAITING) != 0) {
                status = THREAD_STATUS_WAIT;
            } else if ((state & JVMTI_THREAD_STATE_RUNNABLE) != 0) {
                status = THREAD_STATUS_RUNNING;
            }
        }
        return status;
    }

    public boolean isSuspended() { //fixme jjh
        // If we want to support doing this for a VM which was being
        // debugged, then we need to fix this.
        // In the meantime, we will say all threads are suspended,
        // otherwise, some things won't work, like the jdb 'up' cmd.
        return true;
    }

    public boolean isAtBreakpoint() { //fixme jjh
        // If we want to support doing this for a VM which was being
        // debugged, then we need to fix this.
        return false;
    }

    public ThreadGroupReference threadGroup() {
        return vm.threadGroupMirror((Instance)OopUtilities.threadOopGetThreadGroup(ref()));
    }

    public int frameCount() throws IncompatibleThreadStateException  { //fixme jjh
        privateFrames(0, -1);
        return frames.size();
    }

    public List<StackFrame> frames() throws IncompatibleThreadStateException  {
        return privateFrames(0, -1);
    }

    public StackFrameImpl frame(int index) throws IncompatibleThreadStateException  {
        return (StackFrameImpl) privateFrames(index, 1).get(0);
    }

    public List<StackFrame> frames(int start, int length)
                              throws IncompatibleThreadStateException  {
        if (length < 0) {
            throw new IndexOutOfBoundsException("length must be greater than or equal to zero");
        }
        return privateFrames(start, length);
    }

    /**
     * Private version of frames() allows "-1" to specify all
     * remaining frames.
     */

    private List<StackFrame> privateFrames(int start, int length)
                              throws IncompatibleThreadStateException  {
        if (myJavaThread == null) {
            // for zombies and yet-to-be-started threads we need to throw exception
            throw new IncompatibleThreadStateException();
        }
        if (frames == null) {
            frames = new ArrayList<StackFrame>(10);
            JavaVFrame myvf = myJavaThread.getLastJavaVFrameDbg();
            int id = 0;
            while (myvf != null) {
                StackFrameImpl myFrame = new StackFrameImpl(vm, this, myvf, id++);
                //fixme jjh null should be a Location
                frames.add(myFrame);
                try {
                    myvf = myvf.javaSender();
                } catch (Exception e) {
                    e.printStackTrace(); // skip
                    break;
                }
            }
        }

        List<StackFrame> retVal;
        if (frames.size() == 0) {
            retVal = new ArrayList<StackFrame>(0);
        } else {
            int toIndex = start + length;
            if (length == -1) {
                toIndex = frames.size();
            }
            retVal = frames.subList(start, toIndex);
        }
        return Collections.unmodifiableList(retVal);
    }

    // refer to JvmtiEnvBase::get_owned_monitors
    public List<ObjectReference> ownedMonitors()  throws IncompatibleThreadStateException {
        if (!vm.canGetOwnedMonitorInfo()) {
            throw new UnsupportedOperationException();
        }

        if (myJavaThread == null) {
           throw new IncompatibleThreadStateException();
        }

        if (ownedMonitors != null) {
            return ownedMonitors;
        }

        ownedMonitorsWithStackDepth();

        for (com.sun.jdi.MonitorInfo monitorInfo : ownedMonitorsInfo) {
            //FIXME : Change the MonitorInfoImpl cast to com.sun.jdi.MonitorInfo
            //        when hotspot start building with jdk1.6.
            ownedMonitors.add(monitorInfo.monitor());
        }

        return ownedMonitors;
    }

    // new method since 1.6.
    // Real body will be supplied later.
    public List<com.sun.jdi.MonitorInfo> ownedMonitorsAndFrames() throws IncompatibleThreadStateException {
        if (!vm.canGetMonitorFrameInfo()) {
            throw new UnsupportedOperationException(
                "target does not support getting Monitor Frame Info");
        }

        if (myJavaThread == null) {
           throw new IncompatibleThreadStateException();
        }

        if (ownedMonitorsInfo != null) {
            return ownedMonitorsInfo;
        }

        ownedMonitorsWithStackDepth();
        return ownedMonitorsInfo;
    }

    private void ownedMonitorsWithStackDepth() {

        ownedMonitorsInfo = new ArrayList<com.sun.jdi.MonitorInfo>();
        List<OopHandle> lockedObjects = new ArrayList<OopHandle>(); // List<OopHandle>
        List<Integer> stackDepth = new ArrayList<Integer>(); // List<int>
        ObjectMonitor waitingMonitor = myJavaThread.getCurrentWaitingMonitor();
        ObjectMonitor pendingMonitor = myJavaThread.getCurrentPendingMonitor();
        OopHandle waitingObj = null;
        if (waitingMonitor != null) {
            // save object of current wait() call (if any) for later comparison
            waitingObj = waitingMonitor.object();
        }
        OopHandle pendingObj = null;
        if (pendingMonitor != null) {
            // save object of current enter() call (if any) for later comparison
            pendingObj = pendingMonitor.object();
        }

        JavaVFrame frame = myJavaThread.getLastJavaVFrameDbg();
        int depth=0;
        while (frame != null) {
            for (Object frameMonitor : frame.getMonitors()) {
                MonitorInfo mi = (MonitorInfo) frameMonitor;
                if (mi.eliminated() && frame.isCompiledFrame()) {
                    continue; // skip eliminated monitor
                }
                OopHandle obj = mi.owner();
                if (obj == null) {
                    // this monitor doesn't have an owning object so skip it
                    continue;
                }

                if (obj.equals(waitingObj)) {
                    // the thread is waiting on this monitor so it isn't really owned
                    continue;
                }

                if (obj.equals(pendingObj)) {
                    // the thread is pending on this monitor so it isn't really owned
                    continue;
                }

                boolean found = false;
                for (Object lockedObject : lockedObjects) {
                    // check for recursive locks
                    if (obj.equals(lockedObject)) {
                        found = true;
                        break;
                    }
                }
                if (found) {
                    // already have this object so don't include it
                    continue;
                }
                // add the owning object to our list
                lockedObjects.add(obj);
                stackDepth.add(depth);
            }
            frame = frame.javaSender();
            depth++;
        }

        // now convert List<OopHandle> to List<ObjectReference>
        ObjectHeap heap = vm.saObjectHeap();
        Iterator<Integer> stk = stackDepth.iterator();
        for (OopHandle lockedObject : lockedObjects) {
            Oop obj = heap.newOop(lockedObject);
            ownedMonitorsInfo.add(new MonitorInfoImpl(vm, vm.objectMirror(obj), this, stk.next()));
        }
    }

    // refer to JvmtiEnvBase::get_current_contended_monitor
    public ObjectReference currentContendedMonitor()
                              throws IncompatibleThreadStateException  {
        if (!vm.canGetCurrentContendedMonitor()) {
            throw new UnsupportedOperationException();
        }

        if (myJavaThread == null) {
           throw new IncompatibleThreadStateException();
        }
        ObjectMonitor mon = myJavaThread.getCurrentWaitingMonitor();
        if (mon == null) {
           // thread is not doing an Object.wait() call
           mon = myJavaThread.getCurrentPendingMonitor();
           if (mon != null) {
               OopHandle handle = mon.object();
               // If obj == NULL, then ObjectMonitor is raw which doesn't count
               // as contended for this API
               return vm.objectMirror(vm.saObjectHeap().newOop(handle));
           } else {
               // no contended ObjectMonitor
               return null;
           }
        } else {
           // thread is doing an Object.wait() call
           OopHandle handle = mon.object();
           if (Assert.ASSERTS_ENABLED) {
               Assert.that(handle != null, "Object.wait() should have an object");
           }
           Oop obj = vm.saObjectHeap().newOop(handle);
           return vm.objectMirror(obj);
        }
    }


    public void popFrames(StackFrame frame) {
        vm.throwNotReadOnlyException("ThreadReference.popFrames()");
    }

    public void forceEarlyReturn(Value returnValue) {
        vm.throwNotReadOnlyException("ThreadReference.forceEarlyReturn()");
    }

    public String toString() {
        return "instance of " + referenceType().name() +
               "(name='" + name() + "', " + "id=" + uniqueID() + ")";
    }

    @Override
    byte typeValueKey() {
        return JDWP.Tag.THREAD;
    }
}
