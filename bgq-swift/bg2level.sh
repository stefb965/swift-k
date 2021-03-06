#!/bin/bash

#set -x

mname=$(hostname)

# OUTERBLOCK_SIZE is the size of outer block

# vesta and mira has different path than cetus
if [[ $mname == *vesta* || $mname == *mira* ]]; then
    export PATH=/soft/cobalt/bgq_hardware_mapper:$PATH
else
    export PATH=/soft/cobalt/cetus/bgq_hardware_mapper:$PATH    
fi


# Prepare shape based on subblock size provided by user in sites environment
case "$SUBBLOCK_SIZE" in
1) SHAPE="1x1x1x1x1"
;;
8) SHAPE="1x2x2x2x1"
;;
16) SHAPE="2x2x2x2x1"
;;
32) SHAPE="2x2x2x2x2"
;;
64) SHAPE="2x2x4x2x2"
;;
128) SHAPE="2x4x4x2x2"
;;
256) SHAPE="2x4x4x4x2"
;;
512) SHAPE="4x4x4x4x2"
;;
*) echo "SUBBLOCK_SIZE not set or incorrectly set: will not use subblock jobs"
;;
esac


# If subblock size is provided, do subblock business
if [ "$SUBBLOCK_SIZE"_ != "_" ]; then
    # sub-block size larger than 512 nodes
    if [ "$SUBBLOCK_SIZE" -gt 512 ]; then

        export SWIFT_SUBBLOCKS=$(get-bootable-blocks --size $SUBBLOCK_SIZE $COBALT_PARTNAME)
        export SWIFT_SUBBLOCK_ARRAY=($SWIFT_SUBBLOCKS)

        if [ "_$SWIFT_SUBBLOCKS" = _ ]; then
          echo ERROR: "$0": SWIFT_SUBBLOCKS is null.
          exit 1
        fi
        BLOCK=${SWIFT_SUBBLOCK_ARRAY[$SWIFT_JOB_SLOT]}
        
        #Some logging
        echo "$0": running BLOCK="$BLOCK" SLOT="$SWIFT_JOB_SLOT"
        echo "$0": running cmd: "$0" args: "$@"
        echo "$0": running runjob --block "$BLOCK" : "$@"
        
        boot-block --block $BLOCK
        runjob -p 16 --block $BLOCK : "$@"
        boot-block --block $BLOCK --free 

        echo "Runjob finished"

    else
        export SWIFT_SUBBLOCKS=$(get-corners.py "$COBALT_PARTNAME" $SHAPE)
        export SWIFT_SUBBLOCK_ARRAY=($SWIFT_SUBBLOCKS)
        
        if [ "_$SWIFT_SUBBLOCKS" = _ ]; then
          echo ERROR: "$0": SWIFT_SUBBLOCKS is null.
          exit 1
        fi
        
        nsb=${#SWIFT_SUBBLOCK_ARRAY[@]}
        
        CORNER=${SWIFT_SUBBLOCK_ARRAY[$SWIFT_JOB_SLOT]}
        
        #Some logging
        echo "$0": running BLOCK="$COBALT_PARTNAME" SLOT="$SWIFT_JOB_SLOT"
        echo "$0": running cmd: "$0" args: "$@"
        echo "$0": runjob --block "$COBALT_PARTNAME" --corner "$CORNER" --shape "$SHAPE" -p 16 --np "$((16*$SUBBLOCK_SIZE))" : "$@" 
        
        runjob --block "$COBALT_PARTNAME" --corner "$CORNER" --shape "$SHAPE" -p 16 --np "$((16*$SUBBLOCK_SIZE))" : "$@"
        
        echo "Runjob finished."
    fi
else
    # run w/o subblocks if no subblock size provided
    echo "Running in nonsubblock mode."
    echo "$0": running runjob -p 16 --block $COBALT_PARTNAME : "$@"

    #strace -o "$HOME/strace.runjob.out" runjob --strace none -p 16 --block $COBALT_PARTNAME : "$@"
    runjob -p 16 --block $COBALT_PARTNAME : "$@"

    echo "Finished Running in nonsubblock mode."
fi

exit 0

