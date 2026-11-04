"""Finto server inWebo + client OTP Python originale. Scrive responses.txt e python_urls.txt."""
import hashlib, os, pickle, sys
OUT = os.environ.get('OTP_TEST_OUT', '/tmp/otptest-run')
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from Crypto.PublicKey import RSA
from Crypto.Cipher import AES, PKCS1_OAEP
from Crypto.Hash import SHA256

DEV, ALEA, SMS, PIN = '0123456789abcdef', 'f' * 32, '123456', '4321'

kfact_key = RSA.generate(1024, e=17)
kiw_key = RSA.generate(1024, e=17)
temp_key = RSA.generate(1024, e=17)


def srv_enc(key, data):
    """Cifratura con esponente privato, che il client "decifra" con quello pubblico."""
    fake = RSA.construct((key.n, key.d))
    c = PKCS1_OAEP.new(fake, hashAlgo=SHA256)
    return b''.join(c.encrypt(data[i:i + 62]) for i in range(0, len(data), 62)).hex()


def nbytes(n):
    return n.to_bytes((n.bit_length() + 7) // 8, 'big')


serial = DEV + '/_/' + ALEA
kma = hashlib.sha256((PIN + ';' + serial).encode()).hexdigest()[:32]
aes = AES.new(bytes.fromhex(kma), AES.MODE_ECB)
k0, k1 = bytes(range(16)), bytes(range(16, 32))


def xml(root, **kv):
    return '<?xml version="1.0" encoding="UTF-8"?><%s>%s</%s>' % (root, ''.join(f'<{k}>{v}</{k}>' for k, v in kv.items()), root)


responses = [
    xml('ActionSetup', err='OK', Kiw=srv_enc(kfact_key, nbytes(kiw_key.n)), Kfact='%x' % kfact_key.n, pinmode='1'),
    xml('ActionFinalize', err='OK', id='DEV42', K0=aes.encrypt(k0).hex(), K1=aes.encrypt(k1).hex(), ms_n='1',
        challenge='CH1', ms_key=srv_enc(kfact_key, nbytes(temp_key.n)), ms_id='MS9', s_id='SID7'),
    xml('ActionFinalize', err='OK', s_n='1', Tsync='5f00', dK1='abcd'),
    xml('ActionSetup', err='OK', challenge='CH2'),
    xml('ActionFinalize', err='OK', defi='77', dK1='ef01'),
    xml('ActionSetup', err='OK', challenge='CH3'),
    xml('ActionFinalize', err='OK', defi='78'),
]
open(os.path.join(OUT, 'responses.txt'), 'w').write('\n'.join(responses) + '\n')

from pkg.otp import otp as otp_mod, oaep
import requests

counter = [0]


def rng(n):
    out = hashlib.sha256(b'rng' + str(counter[0]).encode()).digest()[:n]
    counter[0] += 1
    return out


oaep.Random.get_random_bytes = rng
otp_mod.token_bytes = rng

urls, queue = [], list(responses)


class R:
    def __init__(self, t): self.text = t


def fake_get(url, headers=None, params=None, **kw):
    urls.append(requests.Request('GET', url, params=params).prepare().url)
    return R(queue.pop(0))


otp_mod.requests.get = fake_get
otp_mod.save_otp = lambda *a, **k: None

o = otp_mod.Otp('bb8e981582b0f31353108fb020bead1c', device_id=DEV)
o.iwalea = ALEA
o.smsCode, o.codepin = SMS, PIN
assert o.activation_start()
assert o.activation_finalyze() == 0
code1 = o.get_otp_code()
o = pickle.loads(pickle.dumps(o))
code2 = o.get_otp_code()
open(os.path.join(OUT, 'python_urls.txt'), 'w').write('\n'.join(urls) + '\nCODE1 ' + code1 + '\nCODE2 ' + code2 + '\n')
print('python ok', code1, code2, len(urls), 'richieste')
