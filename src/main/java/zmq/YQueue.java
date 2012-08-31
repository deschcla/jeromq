/*
    Copyright (c) 2009-2011 250bpm s.r.o.
    Copyright (c) 2007-2009 iMatix Corporation
    Copyright (c) 2007-2011 Other contributors as noted in the AUTHORS file

    This file is part of 0MQ.

    0MQ is free software; you can redistribute it and/or modify it under
    the terms of the GNU Lesser General Public License as published by
    the Free Software Foundation; either version 3 of the License, or
    (at your option) any later version.

    0MQ is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Lesser General Public License for more details.

    You should have received a copy of the GNU Lesser General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package zmq;

import java.lang.reflect.Array;


public class YQueue<T extends IReplaceable> {

    //  Individual memory chunk to hold N elements.
    private class Chunk
    {
         final T []values;
         final long[] pos;
         Chunk prev;
         Chunk next;
         
         @SuppressWarnings("unchecked")
         protected Chunk(Class<T> klass, int size, long memory_ptr, boolean allocate) {
             values = (T[])(Array.newInstance(klass, size));
             pos = new long[size];
             assert values != null;
             prev = next = null;
             for (int i=0; i != values.length; i++) {
                 pos[i] = memory_ptr;
                 memory_ptr++;
                 if (allocate) {
                    try {
                        values[i] = klass.newInstance();
                    } catch (Exception e) {
                        throw new ZError.InstantiationException(e);
                    }
                 }
             }
            
         }
         protected void reset() {
             if (!allocate) {
                 for (int i=0; i != values.length; i++) {
                    values[i] = null;
                 }
             }
         }
    };

    //  Back position may point to invalid memory if the queue is empty,
    //  while begin & end positions are always valid. Begin position is
    //  accessed exclusively be queue reader (front/pop), while back and
    //  end positions are accessed exclusively by queue writer (back/push).
    private Chunk begin_chunk;
    private int begin_pos;
    private Chunk back_chunk;
    private int back_pos;
    private Chunk end_chunk;
    private int end_pos;
    private Chunk spare_chunk;
    private final Class<T> klass;
    private final int size;
    private boolean allocate;

    //  People are likely to produce and consume at similar rates.  In
    //  this scenario holding onto the most recently freed chunk saves
    //  us from having to call malloc/free.
    
    private long memory_ptr;
    

    public YQueue(Class<T> klass, int size, boolean allocate) {
        
        this.klass = klass;
        this.size = size;
        this.allocate = allocate;
        memory_ptr = 0;
        begin_chunk = new Chunk(klass, size, memory_ptr, allocate);
        memory_ptr += size;
        begin_pos = 0;
        back_pos = 0;
        back_chunk = null;
        spare_chunk = begin_chunk;
        end_chunk = begin_chunk;
        end_pos = 0;
    }
    
    public long front_pos() {
        return begin_chunk.pos[begin_pos];
    }
    
    //  Returns reference to the front element of the queue.
    //  If the queue is empty, behaviour is undefined.
    public T front() {
        return begin_chunk.values [begin_pos];
    }

    public long back_pos() {
        return back_chunk.pos [back_pos];
    }

    //  Returns reference to the back element of the queue.
    //  If the queue is empty, behaviour is undefined.
    public T back() {
        return back_chunk.values [back_pos];
    }
    
    public T back(T val) {
        if (allocate)
            back_chunk.values[back_pos].replace(val);
        else
            back_chunk.values [back_pos] = val;
        return val;
    }


    public void pop() {
        begin_pos++;
        if (begin_pos == size) {
            begin_chunk.reset();
            begin_chunk = begin_chunk.next;
            begin_chunk.prev = null;
            begin_pos = 0;
        }
    }

    //  Adds an element to the back end of the queue.
    public void push() {
        back_chunk = end_chunk;
        back_pos = end_pos;

        end_pos ++;
        if (end_pos != size)
            return;

        Chunk sc = spare_chunk;
        if (sc != begin_chunk) {
            // writer thread could see old begin_chunk value, 
            // but it would just waste a chunk
            spare_chunk = spare_chunk.next;
            end_chunk.next = sc;
            sc.prev = end_chunk;
        } else {
            end_chunk.next =  new Chunk(klass, size, memory_ptr, allocate);
            memory_ptr += size;
            end_chunk.next.prev = end_chunk;
        }
        end_chunk = end_chunk.next;
        end_pos = 0;
    }

    //  Removes element from the back end of the queue. In other words
    //  it rollbacks last push to the queue. Take care: Caller is
    //  responsible for destroying the object being unpushed.
    //  The caller must also guarantee that the queue isn't empty when
    //  unpush is called. It cannot be done automatically as the read
    //  side of the queue can be managed by different, completely
    //  unsynchronised thread.
    public void unpush() {
        //  First, move 'back' one position backwards.
        if (back_pos > 0)
            back_pos--;
        else {
            back_pos = size - 1;
            back_chunk = back_chunk.prev;
        }

        //  Now, move 'end' position backwards. Note that obsolete end chunk
        //  is not used as a spare chunk. The analysis shows that doing so
        //  would require free and atomic operation per chunk deallocated
        //  instead of a simple free.
        if (end_pos > 0)
            end_pos--;
        else {
            end_pos = size - 1;
            end_chunk = end_chunk.prev;
            end_chunk.next = null;
        }
    }



}
