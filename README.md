# Performance

1. applicable to all reads. If block is in the cache, it gets read to RAM
2. applicable to all write. Block gets written to RAM, write is delayed
3. FS does not support checking for the existence of dentries. Implementing this algorithm with the current structure will set minimum CPU/DISK complexity to O(n)
4. even the data is read, it gets written on cache clean/rotate. Dirty state has to be implemented

### Format
1. CPU - O(n)
2. DISK - O(n)
3. RAM - allocation of 2 deqeues of int, 1 - worst size - INODES_PER_BLOCK * 10% of #blocks, 2 - worst sice #blocks

### FSCK
1. CPU - O(n*n) Optimized loop - avoid processing inactive entries
2. DISK - O(n)
3. RAM - same as for format + inodeBlocks * BLOCK_SIZE size buffer with all inodes

### Create File/Folder
1. CPU - O(1)
2. DISK - O(1)
3. RAM - cache the contents of all read blocks

### Move File/Folder/Update File Name
1. CPU - O(n)
2. DISK - O(n)
3. RAM - cache the contents of all read blocks, buffer for all dentries in folder

### Locating file/folder
1. CPU O(n*n)
2. DISK O(n*n), when path contains n elements and every element is on the n-th page of the parent's folder. Due to caching, we do O(n) unnecessary writes of folder items
3. RAM - cache the contents of all read blocks, buffer for all dentries in 1 folder

### Reading file
1. CPU O(n)
2. DISK O(n)
3. RAM - cache the contents

### Writing/truncating file
1. CPU O(n)
2. DISK O(n)
3. RAM - cache the contents

# Scalability
1. Filesystem is suitable to store the files up to 4 Mb. 
2. By increasing the block size we can increase the file size, but this will lead into increased internal fragmentation
3. In order to get rid of O(n*n), B-Trees should be implemented both for inode pointers and dentries
4. In order to get rid of dentry internal fragmentation, another storage concept for filenames should be implemented
5. Implementation does not support multi-threading, so won't scale horizontally