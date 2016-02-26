type file;

// Contingency Analysis App
app (file _ctg1, file _ctg2, file _out, file _err) ca (file _infile, file _contfile, file _ieeraw)
{
   bgsh "/home/ketan/gridpack-examples/contingency_analysis/ca.x" @_infile stdout=@_out stderr=@_err; 
}

// Files
file infiles[] <filesys_mapper; location="infiles", suffix=".xml">;
file logs[];
file contfile<"contingencies.xml">;
file ieeeraw<"IEEE14_ca.raw">;

// Run scaling tests
foreach infile,i in infiles {
   file out <single_file_mapper; file=strcat("logs/ca.", i, ".stdout")>;
   file err <single_file_mapper; file=strcat("logs/ca.", i, ".stderr")>;
   string[] fname=strsplit(filename(infile),"\\/");
   file ctg1 <single_file_mapper; file=strcat("CTG1", fname[1],".out")>;
   file ctg2 <single_file_mapper; file=strcat("CTG2", fname[1],".out")>;

   (ctg1, ctg2, out, err) = ca(infile, contfile, ieeeraw);
   logs[i] = out;
}

