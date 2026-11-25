use hmac::Hmac;
use jni::objects::{JByteArray, JClass};
use jni::sys::{jbyteArray, jint};
use jni::JNIEnv;
use sha2::Sha256;
use zeroize::Zeroize;

type HmacSha256 = Hmac<Sha256>;

const MIN_ITERATIONS: i32 = 10_000;
const MAX_ITERATIONS: i32 = 2_000_000;
const MAX_SALT_LEN: usize = 4096;

fn as_u8(v: &mut [i8]) -> &mut [u8] {
    unsafe { std::slice::from_raw_parts_mut(v.as_mut_ptr().cast(), v.len()) }
}

fn derive_raw(password: &[u8], salt: &[u8], iterations: u32, dk_len: usize) -> Vec<u8> {
    let mut out = vec![0u8; dk_len];
    let _ = pbkdf2::pbkdf2::<HmacSha256>(password, salt, iterations, &mut out);
    out
}

#[no_mangle]
pub extern "system" fn Java_com_copy_account_security_NativeKdf_derive(
    mut env: JNIEnv,
    _class: JClass,
    password: JByteArray,
    salt: JByteArray,
    iterations: jint,
    bits: jint,
) -> jbyteArray {
    let fail = |env: &mut JNIEnv, msg: &str| -> jbyteArray {
        env.throw_new("java/lang/IllegalArgumentException", msg).ok();
        std::ptr::null_mut()
    };

    if iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS {
        return fail(&mut env, "迭代次数越界");
    }
    let dk_len = match bits {
        256 => 32usize,
        512 => 64usize,
        _ => return fail(&mut env, "不支持的输出长度"),
    };

    let pass_len = match env.get_array_length(&password) {
        Ok(n) => n as usize,
        Err(_) => return fail(&mut env, "密码参数读取失败"),
    };
    let mut pwd = vec![0i8; pass_len];
    if env.get_byte_array_region(&password, 0, &mut pwd).is_err() {
        as_u8(&mut pwd).zeroize();
        return fail(&mut env, "密码参数读取失败");
    }
    let salt_len = match env.get_array_length(&salt) {
        Ok(n) => n as usize,
        Err(_) => {
            as_u8(&mut pwd).zeroize();
            return fail(&mut env, "盐参数读取失败");
        }
    };
    if salt_len > MAX_SALT_LEN {
        as_u8(&mut pwd).zeroize();
        return fail(&mut env, "盐长度越界");
    }
    let mut slt = vec![0i8; salt_len];
    if env.get_byte_array_region(&salt, 0, &mut slt).is_err() {
        as_u8(&mut pwd).zeroize();
        as_u8(&mut slt).zeroize();
        return fail(&mut env, "盐参数读取失败");
    }

    let dk = derive_raw(as_u8(&mut pwd), as_u8(&mut slt), iterations as u32, dk_len);
    as_u8(&mut pwd).zeroize();
    as_u8(&mut slt).zeroize();

    let out = match env.new_byte_array(dk_len as jint) {
        Ok(a) => a,
        Err(_) => return fail(&mut env, "输出数组创建失败"),
    };
    let dk_i8: &[i8] = unsafe { std::slice::from_raw_parts(dk.as_ptr().cast(), dk.len()) };
    if env.set_byte_array_region(&out, 0, dk_i8).is_err() {
        return fail(&mut env, "输出拷贝失败");
    }
    out.into_raw()
}

#[cfg(test)]
mod tests {
    use super::derive_raw;

    fn hex(s: &str) -> Vec<u8> {
        s.split_whitespace()
            .map(|b| u8::from_str_radix(b, 16).unwrap())
            .collect()
    }

    #[test]
    fn rfc7914_vector_1() {
        let dk = derive_raw(b"passwd", b"salt", 1, 64);
        assert_eq!(
            hex(
                "55 ac 04 6e 56 e3 08 9f ec 16 91 c2 25 44 b6 05
                 f9 41 85 21 6d de 04 65 e6 8b 9d 57 c2 0d ac bc
                 49 ca 9c cc f1 79 b6 45 99 16 64 b3 9d 77 ef 31
                 7c 71 b8 45 b1 e3 0b d5 09 11 20 41 d3 a1 97 83"
            ),
            dk
        );
    }

    #[test]
    fn rfc7914_vector_2() {
        let dk = derive_raw(b"Password", b"NaCl", 80_000, 64);
        assert_eq!(
            hex(
                "4d dc d8 f6 0b 98 be 21 83 0c ee 5e f2 27 01 f9
                 64 1a 44 18 d0 4c 04 14 ae ff 08 87 6b 34 ab 56
                 a1 d4 25 a1 22 58 33 54 9a db 84 1b 51 c9 b3 17
                 6a 27 2b de bb a1 d0 78 47 8f 62 b3 97 f3 3c 8d"
            ),
            dk
        );
    }

    #[test]
    fn dklen_prefix_property() {
        let pass = "测试密码123";
        let salt = "盐值盐值盐值盐值";
        let short = derive_raw(pass.as_bytes(), salt.as_bytes(), 1234, 32);
        let long = derive_raw(pass.as_bytes(), salt.as_bytes(), 1234, 64);
        assert_eq!(short, &long[..32]);
    }
}
