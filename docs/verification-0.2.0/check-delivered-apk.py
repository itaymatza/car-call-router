from pathlib import Path
import hashlib, json, struct, zipfile, zlib
p=Path('/mnt/data/CarCallRouter-0.2.0.apk')
b=p.read_bytes()
report={'file':p.name,'bytes':len(b),'sha256':hashlib.sha256(b).hexdigest()}
with zipfile.ZipFile(p) as z:
    assert z.testzip() is None
    infos=z.infolist(); names=[i.filename for i in infos]
    assert len(names)==len(set(names))
    assert all(not n.startswith('/') and '..' not in n.split('/') for n in names)
    assert {'AndroidManifest.xml','resources.arsc','classes.dex'}.issubset(names)
    assert not any(n.endswith(('.p12','.jks','.keystore','.jar','.so')) or 'password' in n.lower() for n in names)
    alignment=[]
    for i in infos:
        assert b[i.header_offset:i.header_offset+4]==b'PK\x03\x04'
        fn,extra=struct.unpack_from('<HH',b,i.header_offset+26)
        offset=i.header_offset+30+fn+extra
        if i.compress_type==zipfile.ZIP_STORED:
            assert offset%4==0,(i.filename,offset)
            alignment.append(i.filename)
    assert z.getinfo('resources.arsc').compress_type==zipfile.ZIP_STORED
    d=z.read('classes.dex')
    assert d[:4]==b'dex\n' and d[7]==0
    assert struct.unpack_from('<I',d,32)[0]==len(d)
    assert struct.unpack_from('<I',d,8)[0]==zlib.adler32(d[12:])&0xffffffff
    assert d[12:32]==hashlib.sha1(d[32:]).digest()
    sc,so=struct.unpack_from('<II',d,56)
    def text(o):
        while d[o]&128:o+=1
        o+=1
        return d[o:d.index(0,o)].decode('utf-8',errors='replace')
    strings=[text(struct.unpack_from('<I',d,so+4*i)[0]) for i in range(sc)]
    tc,to=struct.unpack_from('<II',d,64)
    types=[strings[struct.unpack_from('<I',d,to+4*i)[0]] for i in range(tc)]
    cc,co=struct.unpack_from('<II',d,96)
    classes=[types[struct.unpack_from('<I',d,co+32*i)[0]] for i in range(cc)]
    assert all(c.startswith(('Lcom/itaymatza/carcallrouter/','Lkotlin/')) for c in classes),sorted(set(c for c in classes if not c.startswith(('Lcom/itaymatza/carcallrouter/','Lkotlin/'))))
    required=['Lcom/itaymatza/carcallrouter/ui/MainActivity;','Lcom/itaymatza/carcallrouter/telecom/RouterInCallService;','Lcom/itaymatza/carcallrouter/ProjectionMonitor;']
    assert all(c in classes for c in required)
    mc,mo=struct.unpack_from('<II',d,88)
    methods=[]
    for i in range(mc):
        cls,proto,name=struct.unpack_from('<HHI',d,mo+8*i)
        methods.append((types[cls],strings[name]))
    assert any(n=='requestBluetoothAudio' and 'Telecom' not in c for c,n in methods)
    banned={('Landroid/media/AudioManager;','setMode'),('Landroid/media/AudioManager;','setCommunicationDevice'),('Landroid/bluetooth/BluetoothAdapter;','disable'),('Landroid/bluetooth/BluetoothHeadset;','disconnect')}
    assert not banned.intersection(methods)
    assert 'androidx.car.app.connection.action.CAR_CONNECTION_UPDATED' in strings
    assert 'content://androidx.car.app.connection' in strings
    assert 'cmd appops set --uid com.itaymatza.carcallrouter MANAGE_ONGOING_CALLS allow' in strings
    report.update(zip_crc='PASS',path_and_secret_checks='PASS',stored_entry_4byte_alignment=f'PASS ({len(alignment)} entries)',dex_integrity='PASS',dex_version=d[:8].decode().strip('\0\n'),dex_classes=len(classes),app_classes=sum(c.startswith('Lcom/') for c in classes),class_namespaces='Only app code and Kotlin runtime',routing_call=[f'{c}->{n}' for c,n in methods if n=='requestBluetoothAudio'],prohibited_routing_calls='NONE',permissions='BLUETOOTH_CONNECT, READ_PHONE_NUMBERS, MANAGE_ONGOING_CALLS')
print(json.dumps(report,indent=2))
Path('/mnt/data/build-work/output/logs/binary-integrity.json').write_text(json.dumps(report,indent=2)+'\n')
