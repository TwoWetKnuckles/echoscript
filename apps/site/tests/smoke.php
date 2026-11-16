<?php

declare(strict_types=1);

use App\Kernel;
use Symfony\Component\HttpFoundation\Request;

require dirname(__DIR__).'/vendor/autoload.php';
(new Symfony\Component\Dotenv\Dotenv())->bootEnv(dirname(__DIR__).'/.env');

function verify(bool $condition, string $message): void
{
    if (!$condition) {
        throw new RuntimeException($message);
    }
}

$kernel = new Kernel('prod', false);
foreach ([['/', 'GET', 200], ['/missing', 'GET', 404], ['/', 'POST', 405]] as [$path, $method, $status]) {
    $request = Request::create('http://localhost'.$path, $method);
    $response = $kernel->handle($request);
    verify($response->getStatusCode() === $status, 'Unexpected route status: '.$method.' '.$path);
    if ($status === 200) {
        $document = new DOMDocument();
        @$document->loadHTML($response->getContent());
        $xpath = new DOMXPath($document);
        verify($xpath->query('//main[@id="main"]')->length === 1, 'Missing primary landmark');
        verify($xpath->query('//script[@type="importmap"]')->length === 1, 'AssetMapper importmap missing');
        $release = json_decode(file_get_contents(dirname(__DIR__, 3).'/downloads/current.json'), true, flags: JSON_THROW_ON_ERROR);
        foreach (['apkUrl', 'zipUrl', 'releaseUrl'] as $key) {
            verify($xpath->query('//a[@href="'.$release[$key].'"]')->length === 1, 'Published release link drift: '.$key);
        }
        verify(str_contains($response->getContent(), $release['apkSha256']), 'Published checksum missing');
        foreach ($xpath->query('//link[@rel="stylesheet"]') as $link) {
            verify(is_file(dirname(__DIR__).'/public'.$link->getAttribute('href')), 'Compiled stylesheet is missing');
        }
        verify($xpath->query('//form|//input[@type="file"]')->length === 0, 'Public portal must not acquire evidence');
    }
    $kernel->terminate($request, $response);
}
$kernel->shutdown();
echo "PASS: public routes, methods, release links, checksum, importmap and local styles\n";
