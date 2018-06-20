;              
CREATE USER IF NOT EXISTS SA SALT '22ab9263b1b02e32' HASH '4b15faea49c432eb8d1ae6f998ca0855e8c301505b2a0be792d5ddbd7f4d3e3b' ADMIN;            
CREATE CACHED TABLE PUBLIC.JOBHISTORYMODEL(
    JOBID UUID NOT NULL,
    COMPNAME VARCHAR(255),
    ENDTIME TIMESTAMP,
    JOBSTATUS VARCHAR(255),
    OUTPUT CLOB,
    STARTTIME TIMESTAMP,
    TIMEDURATION VARCHAR(255),
    TOOLID VARCHAR(255),
    TOOLNAME VARCHAR(255),
    USERNAME VARCHAR(255)
);   
ALTER TABLE PUBLIC.JOBHISTORYMODEL ADD CONSTRAINT PUBLIC.CONSTRAINT_4 PRIMARY KEY(JOBID);      
-- 0 +/- SELECT COUNT(*) FROM PUBLIC.JOBHISTORYMODEL;          